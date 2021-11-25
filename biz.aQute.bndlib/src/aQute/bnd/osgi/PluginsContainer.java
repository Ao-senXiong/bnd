package aQute.bnd.osgi;

import static aQute.bnd.osgi.Processor.removeDuplicateMarker;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;
import static java.util.stream.Collectors.toList;

import java.io.File;
import java.io.OutputStream;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.annotation.ProviderType;
import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.exceptions.SupplierWithException;
import aQute.bnd.header.Attrs;
import aQute.bnd.header.Parameters;
import aQute.bnd.http.HttpClient;
import aQute.bnd.memoize.CloseableMemoize;
import aQute.bnd.osgi.Processor.CL;
import aQute.bnd.result.Result;
import aQute.bnd.service.Registry;
import aQute.bnd.service.RegistryDonePlugin;
import aQute.bnd.service.url.URLConnectionHandler;
import aQute.lib.hex.Hex;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import aQute.libg.cryptography.SHA1;

/**
 * The plugin set for a Processor. Plugins are general service objects and can
 * be any type. The PluginsContainer treats the @{link {@link PluginProvider}
 * special. If it is used with a type, it will expand the when it encounters a
 * {@link ProviderType} plugin, see {@link #getPlugin(Class)} and
 * {@link #getPlugin(Class)}. These provided plugins are _not_ part of the this
 * set so they won't be explicitly returned when {@link #plugins()} is called.
 */
public class PluginsContainer extends AbstractSet<Object> implements Set<Object>, Registry {
	private static final Logger			logger				= LoggerFactory.getLogger(PluginsContainer.class);
	private static final MethodType		defaultConstructor	= methodType(void.class);

	private final Set<Object>			plugins				= new CopyOnWriteArraySet<>();
	private final Set<AutoCloseable>	closeablePlugins	= new CopyOnWriteArraySet<>();

	// The following are only mutated during init(), so they don't need to
	// be concurrent-safe
	private final Set<String>			missingCommand		= new HashSet<>();
	private Workspace					ws;
	private Processor					processor;

	/**
	 * A Plugin Provider provides plugins when accessed with a type selector.
	 * The provided plugins are not part of the container's plugin set.
	 */
	public interface PluginProvider {
		/**
		 * provide the plugins for the given type if the type is equal.
		 *
		 * @param <X> the type of this provider
		 * @param type the class of the provider type, never null
		 * @return a stream with the provided plugins, can be empty
		 */
		<X> Stream<X> provide(Class<X> type);
	}

	@SuppressWarnings("unchecked")
	class AbstractPlugin<T> implements PluginProvider, AutoCloseable {

		final Class<T>	serviceClass;
		final List<T>	externals	= new ArrayList<>();
		final Attrs		attrs;
		boolean			inited;

		AbstractPlugin(Class<T> type, Attrs attrs) {
			serviceClass = type;
			this.attrs = attrs;
		}

		synchronized void init() {
			if (!inited) {
				inited = true;
				if (ws == null)
					return;

				Result<List<Object>> implementations = ws.getExternalPlugins()
					.getImplementations(serviceClass, attrs);
				if (implementations.isOk()) {
					List<T> impls = (List<T>) implementations.unwrap();
					for (T pl : impls) {
						processor.customize(pl, attrs, PluginsContainer.this);
						externals.add(pl);
					}
				} else {
					ws.error("failed to load external plugins for %s, attrs = %s", serviceClass, attrs);
				}
			}
		}

		@Override
		public <X> Stream<X> provide(Class<X> type) {
			if (type != serviceClass)
				return Stream.empty();

			init();
			return (Stream<X>) externals.stream();
		}

		@Override
		public synchronized void close() throws Exception {
			externals.forEach(p -> {
				if (p instanceof AutoCloseable) {
					IO.close((AutoCloseable) p);
				}
			});
			externals.clear();
		}

		@Override
		public String toString() {
			return "AbstractPlugin [serviceClass=" + serviceClass + ", externals=" + externals + ", attrs=" + attrs
				+ ", inited=" + inited + "]";
		}
	}

	protected PluginsContainer() {}

	/**
	 * Init actions occur inside of the first-level memoizer.
	 */
	protected void init(Processor processor) {
		this.processor = processor;
		if (processor instanceof Workspace)
			ws = (Workspace) processor;
		else if (processor instanceof Project)
			ws = ((Project) processor).getWorkspace();

		String spe = processor.getProperty(Constants.PLUGIN);
		if (Constants.NONE.equals(spe)) {
			return;
		}

		// The owner of the plugin is always in there.
		add(processor);
		processor.setTypeSpecificPlugins(this);

		if (processor.getParent() != null) {
			addAll(processor.getParent()
				.getPlugins());
		}

		/*
		 * Look only local
		 */
		spe = processor.mergeLocalProperties(Constants.PLUGIN);
		String pluginPath = processor.mergeProperties(Constants.PLUGINPATH);
		loadPlugins(processor, spe, pluginPath);

	}

	/**
	 * Post init actions must occur outside of the first level memoizer. This
	 * means these actions can reentrantly see the current state of the
	 * PluginsContainer, through the Processor, which may be partially complete
	 * if addExtensions adds more plugins.
	 */
	protected void postInit(Processor processor) {
		processor.addExtensions(this);

		for (RegistryDonePlugin rdp : getPlugins(RegistryDonePlugin.class)) {
			try {
				rdp.done();
			} catch (Exception e) {
				processor.exception(e, "Calling done on %s, gives an exception", rdp);
			}
		}
	}

	/**
	 * Return the set of resolved plugins. Resolving the provider plugins costs
	 * significant time and they are not always needed. For example, an Export
	 * Plugin will only be needed when there is an actual export. However, many
	 * parts of the code depend on the full view. Therefore, this method must
	 * force the lazy resolution of all provided plugins.
	 *
	 * @return a list with resolved plugins.
	 */
	protected Set<Object> plugins() {
		return plugins;
	}

	protected <T> Stream<T> stream(Class<T> type) {
		return plugins().stream()
			.flatMap(o -> expand(o, type))
			.filter(type::isInstance)
			.map(type::cast);
	}

	private Stream<?> expand(Object o, Class<?> type) {
		if (o instanceof PluginProvider)
			return ((PluginProvider) o).provide(type);
		else
			return Stream.of(o);
	}

	/**
	 * Return all plugins while expanding any {@link PluginProvider} that match
	 * the given type
	 */

	@Override
	public <T> T getPlugin(Class<T> type) {
		Optional<T> first = stream(type).findFirst();
		return first.orElse(null);
	}

	/**
	 * Return all plugins while expanding any {@link PluginProvider} that match
	 * the given type
	 */
	@Override
	public <T> List<T> getPlugins(Class<T> type) {
		List<T> list = stream(type).collect(toList());
		return list;
	}

	@Override
	public boolean add(Object plugin) {
		return plugins().add(plugin);
	}

	@Override
	public boolean addAll(Collection<? extends Object> collection) {
		return plugins().addAll(collection);
	}

	@Override
	public boolean remove(Object plugin) {
		return plugins().remove(plugin);
	}

	@Override
	public Iterator<Object> iterator() {
		return plugins().iterator();
	}

	@Override
	public Spliterator<Object> spliterator() {
		return plugins().spliterator();
	}

	@Override
	public Stream<Object> stream() {
		return plugins().stream();
	}

	@Override
	public int size() {
		return plugins().size();
	}

	@Override
	public String toString() {
		return plugins().toString();
	}

	/**
	 * Magic to load the plugins. This is quite tricky actually since we allow
	 * plugins to be downloaded (this is mainly intended for repositories since
	 * in general plugins should use extensions, however to bootstrap the
	 * extensions we need more). Since downloads might need plugins for
	 * passwords and protocols we need to first load the paths specified on the
	 * plugin clause, then check if there are any local plugins (starting with
	 * aQute.bnd and be able to load from our own class loader).
	 * <p>
	 * After that, we load the plugin paths, these can use the built in
	 * connectors.
	 * <p>
	 * Last but not least, we load the remaining plugins.
	 */
	protected void loadPlugins(Processor processor, String pluginString, String pluginPathString) {
		Parameters pluginParameters = new Parameters(pluginString, processor, true);
		CL loader = processor.getLoader();

		// First add the plugin-specific paths from their path: directives
		pluginParameters.stream()
			.flatMapValue(v -> Strings.splitAsStream(v.get(Constants.PATH_DIRECTIVE)))
			.forEachOrdered((key, path) -> {
				try {
					File f = processor.getFile(path)
						.getAbsoluteFile();
					loader.add(f);
				} catch (Exception e) {
					processor.exception(e, "Problem adding path %s to loader for plugin %s", path,
						removeDuplicateMarker(key));
				}
			});

		/*
		 * Try to load any plugins that are local these must start with
		 * aQute.bnd.* and and be possible to load. The main intention of this
		 * code is to load the URL connectors so that any access to remote
		 * plugins can use the connector model.
		 */
		Set<String> loaded = new HashSet<>();
		for (Entry<String, Attrs> entry : pluginParameters.entrySet()) {
			String className = removeDuplicateMarker(entry.getKey());
			Attrs attrs = entry.getValue();

			logger.debug("Trying pre-plugin {}", className);

			Object plugin = loadPlugin(processor, processor.getClass()
				.getClassLoader(), attrs, className, true);
			if (plugin != null) {
				// with the marker!!
				loaded.add(entry.getKey());
			}
		}

		/*
		 * Make sure we load each plugin only once by removing the entries that
		 * were successfully loaded
		 */
		pluginParameters.keySet()
			.removeAll(loaded);

		loadPluginPath(processor, pluginPathString, loader);

		/*
		 * Load the remaining plugins
		 */
		for (Entry<String, Attrs> entry : pluginParameters.entrySet()) {
			String className = removeDuplicateMarker(entry.getKey());
			Attrs attrs = entry.getValue();

			logger.debug("Loading secondary plugin {}", className);

			/*
			 * We can defer the error if the plugin specifies a command name. In
			 * that case, we'll verify that a bnd file does not contain any
			 * references to a plugin command. The reason this feature was added
			 * was to compile plugin classes with the same build.
			 */
			String commands = attrs.get(Constants.COMMAND_DIRECTIVE);

			Object plugin = loadPlugin(processor, loader, attrs, className, commands != null);
			if (plugin == null) {
				Strings.splitAsStream(commands)
					.forEach(missingCommand::add);
			}
		}
	}

	/**
	 * Add the @link {@link Constants#PLUGINPATH} entries (which are file names)
	 * to the class loader. If this file does not exist, and there is a
	 * {@link Constants#PLUGINPATH_URL_ATTR} attribute then we download it first
	 * from that url. You can then also specify a
	 * {@link Constants#PLUGINPATH_SHA1_ATTR} attribute to verify the file.
	 *
	 * @see PLUGINPATH
	 * @param pluginPath the clauses for the plugin path
	 * @param loader The class loader to extend
	 */
	private void loadPluginPath(Processor processor, String pluginPath, CL loader) {
		CloseableMemoize<HttpClient> client = CloseableMemoize
			.closeableSupplier(SupplierWithException.asSupplier(() -> {
				HttpClient c = new HttpClient();
				c.setRegistry(this);
				c.readSettings(processor);
				/*
				 * Allow the URLConnectionHandlers to interact with the
				 * connection so they can sign it or decorate it with a password
				 * etc.
				 */
				stream(URLConnectionHandler.class).forEach(c::addURLConnectionHandler);
				return c;
			}));
		Parameters pluginPathParameters = new Parameters(pluginPath, processor);
		try {
			nextClause: for (Entry<String, Attrs> entry : pluginPathParameters.entrySet()) {

				File f = processor.getFile(entry.getKey())
					.getAbsoluteFile();
				if (!f.isFile()) {

					/*
					 * File does not exist! Check if we need to download
					 */
					String url = entry.getValue()
						.get(Constants.PLUGINPATH_URL_ATTR);
					if (url != null) {
						try {
							logger.debug("downloading {} to {}", url, f.getAbsoluteFile());
							URL u = new URL(url);
							/*
							 * Copy the url to the file
							 */
							IO.mkdirs(f.getParentFile());
							try (Resource resource = Resource.fromURL(u, client.get())) {
								try (OutputStream out = IO.outputStream(f)) {
									resource.write(out);
								}
								long lastModified = resource.lastModified();
								if (lastModified > 0L) {
									f.setLastModified(lastModified);
								}
							}

							/*
							 * If there is a sha specified, we verify the
							 * download of the the file.
							 */
							String digest = entry.getValue()
								.get(Constants.PLUGINPATH_SHA1_ATTR);
							if (digest != null) {
								if (Hex.isHex(digest.trim())) {
									byte[] sha1 = Hex.toByteArray(digest);
									byte[] filesha1 = SHA1.digest(f)
										.digest();
									if (!Arrays.equals(sha1, filesha1)) {
										processor.error(
											"Plugin path: %s, specified url %s and a sha1 but the file does not match the sha",
											entry.getKey(), url);
									}
								} else {
									processor.error(
										"Plugin path: %s, specified url %s and a sha1 '%s' but this is not a hexadecimal",
										entry.getKey(), url, digest);
								}
							}
						} catch (Exception e) {
							processor.exception(e, "Failed to download plugin %s from %s", entry.getKey(), url);
							continue nextClause;
						}
					} else {
						processor.error(
							"No such file %s from %s and no 'url' attribute on the path so it can be downloaded",
							entry.getKey(), processor);
						continue nextClause;
					}
				}
				logger.debug("Adding {} to loader for plugins", f);
				loader.add(f);
			}
		} finally {
			IO.close(client);
		}
	}

	/**
	 * Load a plugin and customize it. If the plugin cannot be loaded then we
	 * return null.
	 *
	 * @param loader Name of the loader
	 * @param attrs
	 * @param className
	 */
	private Object loadPlugin(Processor processor, ClassLoader loader, Attrs attrs, String className,
		boolean ignoreError) {
		try {
			Class<?> c = loader.loadClass(className);
			if (c.isInterface()) {
				AbstractPlugin<?> abstractPlugin = new AbstractPlugin<>(c, attrs);
				addCloseable(abstractPlugin);
				return abstractPlugin;
			} else {
				Object plugin = publicLookup().findConstructor(c, defaultConstructor)
					.invoke();
				processor.customize(plugin, attrs, this);
				addCloseable(plugin);
				return plugin;
			}
		} catch (NoClassDefFoundError e) {
			if (!ignoreError)
				processor.exception(e, "Failed to load plugin %s;%s", className, attrs);
		} catch (ClassNotFoundException e) {
			if (!ignoreError)
				processor.exception(e, "Failed to load plugin %s;%s", className, attrs);
		} catch (Error e) {
			throw e;
		} catch (Throwable e) {
			processor.exception(e, "Unexpected error loading plugin %s-%s", className, attrs);
		}
		return null;
	}

	public void addCloseable(Object plugin) {
		if (plugin instanceof AutoCloseable)
			closeablePlugins.add((AutoCloseable) plugin);
		add(plugin);
	}

	boolean isMissingPlugin(String name) {
		return missingCommand.contains(name);
	}

	protected void close() {
		closeablePlugins.forEach(IO::close);
		closeablePlugins.clear();
		plugins.clear();
		missingCommand.clear();
	}

}
