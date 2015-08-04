package org.apache.catalina.startup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.apache.catalina.Context;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
import org.apache.catalina.deploy.ServletDef;
import org.apache.catalina.deploy.WebXml;
import org.apache.catalina.startup.ContextConfig.AnnotationScanningResult;
import org.apache.catalina.startup.ContextConfig.JavaClassCacheEntry;
import org.apache.catalina.util.Introspection;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.resources.DirContextURLConnection;
import org.apache.naming.resources.ResourceAttributes;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.bcel.classfile.AnnotationElementValue;
import org.apache.tomcat.util.bcel.classfile.AnnotationEntry;
import org.apache.tomcat.util.bcel.classfile.ArrayElementValue;
import org.apache.tomcat.util.bcel.classfile.ClassFormatException;
import org.apache.tomcat.util.bcel.classfile.ClassParser;
import org.apache.tomcat.util.bcel.classfile.ElementValue;
import org.apache.tomcat.util.bcel.classfile.ElementValuePair;
import org.apache.tomcat.util.bcel.classfile.JavaClass;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.scan.Jar;
import org.apache.tomcat.util.scan.JarFactory;

public class AnnotationScanner<T> implements Callable<T>  {

	private static final Log log = LogFactory.getLog( AnnotationScanner.class );
	protected static final StringManager sm =
		        StringManager.getManager(Constants.Package);
	
	/**
     * Set used as the value for {@code JavaClassCacheEntry.sciSet} when there
     * are no SCIs associated with a class.
     */
    private static final Set<ServletContainerInitializer> EMPTY_SCI_SET = Collections.emptySet();
	
    private Map<ServletContainerInitializer, Set<Class<?>>> initializerClassMap;
    private Map<Class<?>, Set<ServletContainerInitializer>> typeInitializerMap;
    private Map<String,JavaClassCacheEntry> javaClassCache;
    private WebXml fragment;
    private boolean handlesTypesOnly;
    private boolean handlesTypesNonAnnotations;
    private boolean handlesTypesAnnotations;
    private Context context;
    
	public AnnotationScanner(
			AnnotationScanningResult result,
			final Map<Class<?>, Set<ServletContainerInitializer>> typeInitializerMap,
			WebXml fragment, boolean handlesTypesOnly, boolean handlesTypesNonAnnotations, boolean handlesTypesAnnotations, Context context) {
		this.initializerClassMap = result.getTaskInitializerClassMap();
		this.typeInitializerMap = typeInitializerMap;
		this.javaClassCache = result.getTaskJavaClasscache();
		this.fragment = fragment;
		this.handlesTypesOnly = handlesTypesOnly;
		this.handlesTypesAnnotations = handlesTypesAnnotations;
		this.handlesTypesNonAnnotations = handlesTypesNonAnnotations;
		this.context = context;
	}
	
	@Override
	public T call() throws Exception {
	    WebXml annotations = new WebXml();
	    // no impact on distributable
	    annotations.setDistributable(true);
	    URL url = fragment.getURL();
	    processAnnotationsUrl(url, annotations,
	            (handlesTypesOnly || fragment.isMetadataComplete()));
	    Set<WebXml> set = new HashSet<WebXml>();
	    set.add(annotations);
	    // Merge annotations into fragment - fragment takes priority
	    fragment.merge(set);
		return (T) new String("success");
	}
	
	protected void processAnnotationsUrl(URL url, WebXml fragment,
            boolean handlesTypesOnly) {
        if (url == null) {
            // Nothing to do.
            return;
        } else if ("jar".equals(url.getProtocol())) {
            processAnnotationsJar(url, fragment, handlesTypesOnly);
        } else if ("jndi".equals(url.getProtocol())) {
            processAnnotationsJndi(url, fragment, handlesTypesOnly);
        } else if ("file".equals(url.getProtocol())) {
            try {
                processAnnotationsFile(
                        new File(url.toURI()), fragment, handlesTypesOnly);
            } catch (URISyntaxException e) {
                log.error(sm.getString("contextConfig.fileUrl", url), e);
            }
        } else {
            log.error(sm.getString("contextConfig.unknownUrlProtocol",
                    url.getProtocol(), url));
        }

    }


    protected void processAnnotationsJar(URL url, WebXml fragment,
            boolean handlesTypesOnly) {

        Jar jar = null;
        InputStream is;

        try {
            jar = JarFactory.newInstance(url);

            jar.nextEntry();
            String entryName = jar.getEntryName();
            while (entryName != null) {
                if (entryName.endsWith(".class")) {
                    is = null;
                    try {
                        is = jar.getEntryInputStream();
                        processAnnotationsStream(
                                is, fragment, handlesTypesOnly);
                    } catch (IOException e) {
                        log.error(sm.getString("contextConfig.inputStreamJar",
                                entryName, url),e);
                    } catch (ClassFormatException e) {
                        log.error(sm.getString("contextConfig.inputStreamJar",
                                entryName, url),e);
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (IOException ioe) {
                                // Ignore
                            }
                        }
                    }
                }
                jar.nextEntry();
                entryName = jar.getEntryName();
            }
        } catch (IOException e) {
            log.error(sm.getString("contextConfig.jarFile", url), e);
        } finally {
            if (jar != null) {
                jar.close();
            }
        }
    }


    protected void processAnnotationsJndi(URL url, WebXml fragment,
            boolean handlesTypesOnly) {
        try {
            URLConnection urlConn = url.openConnection();
            DirContextURLConnection dcUrlConn;
            if (!(urlConn instanceof DirContextURLConnection)) {
                // This should never happen
                sm.getString("contextConfig.jndiUrlNotDirContextConn", url);
                return;
            }

            dcUrlConn = (DirContextURLConnection) urlConn;
            dcUrlConn.setUseCaches(false);

            String type = dcUrlConn.getHeaderField(ResourceAttributes.TYPE);
            if (ResourceAttributes.COLLECTION_TYPE.equals(type)) {
                // Collection
                Enumeration<String> dirs = dcUrlConn.list();
                while (dirs.hasMoreElements()) {
                    String dir = dirs.nextElement();
                    URL dirUrl = new URL(url.toString() + '/' + dir);
                    processAnnotationsJndi(dirUrl, fragment, handlesTypesOnly);
                }

            } else {
                // Single file
                if (url.getPath().endsWith(".class")) {
                    InputStream is = null;
                    try {
                        is = dcUrlConn.getInputStream();
                        processAnnotationsStream(
                                is, fragment, handlesTypesOnly);
                    } catch (IOException e) {
                        log.error(sm.getString("contextConfig.inputStreamJndi",
                                url),e);
                    } catch (ClassFormatException e) {
                        log.error(sm.getString("contextConfig.inputStreamJndi",
                                url),e);
                    } finally {
                        if (is != null) {
                            try {
                                is.close();
                            } catch (Throwable t) {
                                ExceptionUtils.handleThrowable(t);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error(sm.getString("contextConfig.jndiUrl", url), e);
        }
    }


    protected void processAnnotationsFile(File file, WebXml fragment,
            boolean handlesTypesOnly) {

        if (file.isDirectory()) {
            String[] dirs = file.list();
            for (String dir : dirs) {
                processAnnotationsFile(
                        new File(file,dir), fragment, handlesTypesOnly);
            }
        } else if (file.canRead() && file.getName().endsWith(".class")) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                processAnnotationsStream(fis, fragment, handlesTypesOnly);
            } catch (IOException e) {
                log.error(sm.getString("contextConfig.inputStreamFile",
                        file.getAbsolutePath()),e);
            } catch (ClassFormatException e) {
                log.error(sm.getString("contextConfig.inputStreamFile",
                        file.getAbsolutePath()),e);
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                    }
                }
            }
        }
    }


    protected void processAnnotationsStream(InputStream is, WebXml fragment,
            boolean handlesTypesOnly)
            throws ClassFormatException, IOException {

        ClassParser parser = new ClassParser(is);
        JavaClass clazz = parser.parse();
        checkHandlesTypes(clazz);

        if (handlesTypesOnly) {
            return;
        }

        String className = clazz.getClassName();

        AnnotationEntry[] annotationsEntries = clazz.getAnnotationEntries();
        if (annotationsEntries != null) {
            for (AnnotationEntry ae : annotationsEntries) {
                String type = ae.getAnnotationType();
                if ("Ljavax/servlet/annotation/WebServlet;".equals(type)) {
                    processAnnotationWebServlet(className, ae, fragment);
                }else if ("Ljavax/servlet/annotation/WebFilter;".equals(type)) {
                    processAnnotationWebFilter(className, ae, fragment);
                }else if ("Ljavax/servlet/annotation/WebListener;".equals(type)) {
                    fragment.addListener(className);
                } else {
                    // Unknown annotation - ignore
                }
            }
        }
    }

    /**
     * For classes packaged with the web application, the class and each
     * super class needs to be checked for a match with {@link HandlesTypes} or
     * for an annotation that matches {@link HandlesTypes}.
     * @param javaClass
     */
    protected void checkHandlesTypes(JavaClass javaClass) {

        // Skip this if we can
        if (typeInitializerMap.size() == 0)
            return;

        if ((javaClass.getAccessFlags() &
                org.apache.tomcat.util.bcel.Constants.ACC_ANNOTATION) > 0) {
            // Skip annotations.
            return;
        }

        String className = javaClass.getClassName();

        Class<?> clazz = null;
        if (handlesTypesNonAnnotations) {
            // This *might* be match for a HandlesType.
            populateJavaClassCache(className, javaClass);
            JavaClassCacheEntry entry = javaClassCache.get(className);
            if (entry.getSciSet() == null) {
                try {
                    populateSCIsForCacheEntry(entry);
                } catch (StackOverflowError soe) {
                    throw new IllegalStateException(sm.getString(
                            "contextConfig.annotationsStackOverflow",
                            context.getName(),
                            classHierarchyToString(className, entry)));
                }
            }
            if (!entry.getSciSet().isEmpty()) {
                // Need to try and load the class
                clazz = Introspection.loadClass(context, className);
                if (clazz == null) {
                    // Can't load the class so no point continuing
                    return;
                }

                for (ServletContainerInitializer sci : entry.getSciSet()) {
                    Set<Class<?>> classes = initializerClassMap.get(sci);
                    if (classes == null) {
                        classes = new HashSet<Class<?>>();
                        initializerClassMap.put(sci, classes);
                    }
                    classes.add(clazz);
                }
            }
        }

        if (handlesTypesAnnotations) {
            for (Map.Entry<Class<?>, Set<ServletContainerInitializer>> entry :
                    typeInitializerMap.entrySet()) {
                if (entry.getKey().isAnnotation()) {
                    AnnotationEntry[] annotationEntries = javaClass.getAnnotationEntries();
                    if (annotationEntries != null) {
                        for (AnnotationEntry annotationEntry : annotationEntries) {
                            if (entry.getKey().getName().equals(
                                    getClassName(annotationEntry.getAnnotationType()))) {
                                if (clazz == null) {
                                    clazz = Introspection.loadClass(
                                            context, className);
                                    if (clazz == null) {
                                        // Can't load the class so no point
                                        // continuing
                                        return;
                                    }
                                }
                                for (ServletContainerInitializer sci : entry.getValue()) {
                                    initializerClassMap.get(sci).add(clazz);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
    }


    private String classHierarchyToString(String className,
            JavaClassCacheEntry entry) {
        JavaClassCacheEntry start = entry;
        StringBuilder msg = new StringBuilder(className);
        msg.append("->");

        String parentName = entry.getSuperclassName();
        JavaClassCacheEntry parent = javaClassCache.get(parentName);
        int count = 0;

        while (count < 100 && parent != null && parent != start) {
            msg.append(parentName);
            msg.append("->");

            count ++;
            parentName = parent.getSuperclassName();
            parent = javaClassCache.get(parentName);
        }

        msg.append(parentName);

        return msg.toString();
    }

    private void populateJavaClassCache(String className, JavaClass javaClass) {
        if (javaClassCache.containsKey(className)) {
            return;
        }

        // Add this class to the cache
        javaClassCache.put(className, new JavaClassCacheEntry(javaClass));

        populateJavaClassCache(javaClass.getSuperclassName());

        for (String iterface : javaClass.getInterfaceNames()) {
            populateJavaClassCache(iterface);
        }
    }

    private void populateJavaClassCache(String className) {
        if (!javaClassCache.containsKey(className)) {
            String name = className.replace('.', '/') + ".class";
            InputStream is =
                    context.getLoader().getClassLoader().getResourceAsStream(name);
            if (is == null) {
                return;
            }
            ClassParser parser = new ClassParser(is);
            try {
                JavaClass clazz = parser.parse();
                populateJavaClassCache(clazz.getClassName(), clazz);
            } catch (ClassFormatException e) {
                log.debug(sm.getString("contextConfig.invalidSciHandlesTypes",
                        className), e);
            } catch (IOException e) {
                log.debug(sm.getString("contextConfig.invalidSciHandlesTypes",
                        className), e);
            } finally {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private void populateSCIsForCacheEntry(JavaClassCacheEntry cacheEntry) {
        Set<ServletContainerInitializer> result =
                new HashSet<ServletContainerInitializer>();

        // Super class
        String superClassName = cacheEntry.getSuperclassName();
        JavaClassCacheEntry superClassCacheEntry =
                javaClassCache.get(superClassName);

        // Avoid an infinite loop with java.lang.Object
        if (cacheEntry.equals(superClassCacheEntry)) {
            cacheEntry.setSciSet(EMPTY_SCI_SET);
            return;
        }

        // May be null of the class is not present or could not be loaded.
        if (superClassCacheEntry != null) {
            if (superClassCacheEntry.getSciSet() == null) {
                populateSCIsForCacheEntry(superClassCacheEntry);
            }
            result.addAll(superClassCacheEntry.getSciSet());
        }
        result.addAll(getSCIsForClass(superClassName));

        // Interfaces
        for (String interfaceName : cacheEntry.getInterfaceNames()) {
            JavaClassCacheEntry interfaceEntry =
                    javaClassCache.get(interfaceName);
            // A null could mean that the class not present in application or
            // that there is nothing of interest. Either way, nothing to do here
            // so move along
            if (interfaceEntry != null) {
                if (interfaceEntry.getSciSet() == null) {
                    populateSCIsForCacheEntry(interfaceEntry);
                }
                result.addAll(interfaceEntry.getSciSet());
            }
            result.addAll(getSCIsForClass(interfaceName));
        }

        cacheEntry.setSciSet(result.isEmpty() ? EMPTY_SCI_SET : result);
    }

    private Set<ServletContainerInitializer> getSCIsForClass(String className) {
        for (Map.Entry<Class<?>, Set<ServletContainerInitializer>> entry :
                typeInitializerMap.entrySet()) {
            Class<?> clazz = entry.getKey();
            if (!clazz.isAnnotation()) {
                if (clazz.getName().equals(className)) {
                    return entry.getValue();
                }
            }
        }
        return EMPTY_SCI_SET;
    }

    private static final String getClassName(String internalForm) {
        if (!internalForm.startsWith("L")) {
            return internalForm;
        }

        // Assume starts with L, ends with ; and uses / rather than .
        return internalForm.substring(1,
                internalForm.length() - 1).replace('/', '.');
    }

    protected void processAnnotationWebServlet(String className,
            AnnotationEntry ae, WebXml fragment) {
        String servletName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        List<ElementValuePair> evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("name".equals(name)) {
                servletName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (servletName == null) {
            // classname is default servletName as annotation has no name!
            servletName = className;
        }
        ServletDef servletDef = fragment.getServlets().get(servletName);

        boolean isWebXMLservletDef;
        if (servletDef == null) {
            servletDef = new ServletDef();
            servletDef.setServletName(servletName);
            servletDef.setServletClass(className);
            isWebXMLservletDef = false;
        } else {
            isWebXMLservletDef = true;
        }

        boolean urlPatternsSet = false;
        String[] urlPatterns = null;

        // List<ElementValuePair> evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatternsSet = true;
                urlPatterns = processAnnotationsStringArray(evp.getValue());
            } else if ("description".equals(name)) {
                if (servletDef.getDescription() == null) {
                    servletDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (servletDef.getDisplayName() == null) {
                    servletDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (servletDef.getLargeIcon() == null) {
                    servletDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (servletDef.getSmallIcon() == null) {
                    servletDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (servletDef.getAsyncSupported() == null) {
                    servletDef.setAsyncSupported(evp.getValue()
                            .stringifyValue());
                }
            } else if ("loadOnStartup".equals(name)) {
                if (servletDef.getLoadOnStartup() == null) {
                    servletDef
                            .setLoadOnStartup(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLservletDef) {
                    Map<String, String> webXMLInitParams = servletDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            servletDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        servletDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }
            }
        }
        if (!isWebXMLservletDef && urlPatterns != null) {
            fragment.addServlet(servletDef);
        }
        if (urlPatterns != null) {
            if (!fragment.getServletMappings().containsValue(servletName)) {
                for (String urlPattern : urlPatterns) {
                    fragment.addServletMapping(urlPattern, servletName);
                }
            }
        }

    }

    /**
     * process filter annotation and merge with existing one!
     * FIXME: refactoring method too long and has redundant subroutines with
     *        processAnnotationWebServlet!
     * @param className
     * @param ae
     * @param fragment
     */
    protected void processAnnotationWebFilter(String className,
            AnnotationEntry ae, WebXml fragment) {
        String filterName = null;
        // must search for name s. Spec Servlet API 3.0 - 8.2.3.3.n.ii page 81
        List<ElementValuePair> evps = ae.getElementValuePairs();
        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("filterName".equals(name)) {
                filterName = evp.getValue().stringifyValue();
                break;
            }
        }
        if (filterName == null) {
            // classname is default filterName as annotation has no name!
            filterName = className;
        }
        FilterDef filterDef = fragment.getFilters().get(filterName);
        FilterMap filterMap = new FilterMap();

        boolean isWebXMLfilterDef;
        if (filterDef == null) {
            filterDef = new FilterDef();
            filterDef.setFilterName(filterName);
            filterDef.setFilterClass(className);
            isWebXMLfilterDef = false;
        } else {
            isWebXMLfilterDef = true;
        }

        boolean urlPatternsSet = false;
        boolean servletNamesSet = false;
        boolean dispatchTypesSet = false;
        String[] urlPatterns = null;

        for (ElementValuePair evp : evps) {
            String name = evp.getNameString();
            if ("value".equals(name) || "urlPatterns".equals(name)) {
                if (urlPatternsSet) {
                    throw new IllegalArgumentException(sm.getString(
                            "contextConfig.urlPatternValue", className));
                }
                urlPatterns = processAnnotationsStringArray(evp.getValue());
                urlPatternsSet = urlPatterns.length > 0;
                for (String urlPattern : urlPatterns) {
                    filterMap.addURLPattern(urlPattern);
                }
            } else if ("servletNames".equals(name)) {
                String[] servletNames = processAnnotationsStringArray(evp
                        .getValue());
                servletNamesSet = servletNames.length > 0;
                for (String servletName : servletNames) {
                    filterMap.addServletName(servletName);
                }
            } else if ("dispatcherTypes".equals(name)) {
                String[] dispatcherTypes = processAnnotationsStringArray(evp
                        .getValue());
                dispatchTypesSet = dispatcherTypes.length > 0;
                for (String dispatcherType : dispatcherTypes) {
                    filterMap.setDispatcher(dispatcherType);
                }
            } else if ("description".equals(name)) {
                if (filterDef.getDescription() == null) {
                    filterDef.setDescription(evp.getValue().stringifyValue());
                }
            } else if ("displayName".equals(name)) {
                if (filterDef.getDisplayName() == null) {
                    filterDef.setDisplayName(evp.getValue().stringifyValue());
                }
            } else if ("largeIcon".equals(name)) {
                if (filterDef.getLargeIcon() == null) {
                    filterDef.setLargeIcon(evp.getValue().stringifyValue());
                }
            } else if ("smallIcon".equals(name)) {
                if (filterDef.getSmallIcon() == null) {
                    filterDef.setSmallIcon(evp.getValue().stringifyValue());
                }
            } else if ("asyncSupported".equals(name)) {
                if (filterDef.getAsyncSupported() == null) {
                    filterDef
                            .setAsyncSupported(evp.getValue().stringifyValue());
                }
            } else if ("initParams".equals(name)) {
                Map<String, String> initParams = processAnnotationWebInitParams(evp
                        .getValue());
                if (isWebXMLfilterDef) {
                    Map<String, String> webXMLInitParams = filterDef
                            .getParameterMap();
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        if (webXMLInitParams.get(entry.getKey()) == null) {
                            filterDef.addInitParameter(entry.getKey(), entry
                                    .getValue());
                        }
                    }
                } else {
                    for (Map.Entry<String, String> entry : initParams
                            .entrySet()) {
                        filterDef.addInitParameter(entry.getKey(), entry
                                .getValue());
                    }
                }

            }
        }
        if (!isWebXMLfilterDef) {
            fragment.addFilter(filterDef);
            if (urlPatternsSet || servletNamesSet) {
                filterMap.setFilterName(filterName);
                fragment.addFilterMapping(filterMap);
            }
        }
        if (urlPatternsSet || dispatchTypesSet) {
            Set<FilterMap> fmap = fragment.getFilterMappings();
            FilterMap descMap = null;
            for (FilterMap map : fmap) {
                if (filterName.equals(map.getFilterName())) {
                    descMap = map;
                    break;
                }
            }
            if (descMap != null) {
                String[] urlsPatterns = descMap.getURLPatterns();
                if (urlPatternsSet
                        && (urlsPatterns == null || urlsPatterns.length == 0)) {
                    for (String urlPattern : filterMap.getURLPatterns()) {
                        descMap.addURLPattern(urlPattern);
                    }
                }
                String[] dispatcherNames = descMap.getDispatcherNames();
                if (dispatchTypesSet
                        && (dispatcherNames == null || dispatcherNames.length == 0)) {
                    for (String dis : filterMap.getDispatcherNames()) {
                        descMap.setDispatcher(dis);
                    }
                }
            }
        }

    }

    protected String[] processAnnotationsStringArray(ElementValue ev) {
        ArrayList<String> values = new ArrayList<String>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                values.add(value.stringifyValue());
            }
        } else {
            values.add(ev.stringifyValue());
        }
        String[] result = new String[values.size()];
        return values.toArray(result);
    }

    protected Map<String,String> processAnnotationWebInitParams(
            ElementValue ev) {
        Map<String, String> result = new HashMap<String,String>();
        if (ev instanceof ArrayElementValue) {
            ElementValue[] arrayValues =
                ((ArrayElementValue) ev).getElementValuesArray();
            for (ElementValue value : arrayValues) {
                if (value instanceof AnnotationElementValue) {
                    List<ElementValuePair> evps = ((AnnotationElementValue) value)
                            .getAnnotationEntry().getElementValuePairs();
                    String initParamName = null;
                    String initParamValue = null;
                    for (ElementValuePair evp : evps) {
                        if ("name".equals(evp.getNameString())) {
                            initParamName = evp.getValue().stringifyValue();
                        } else if ("value".equals(evp.getNameString())) {
                            initParamValue = evp.getValue().stringifyValue();
                        } else {
                            // Ignore
                        }
                    }
                    result.put(initParamName, initParamValue);
                }
            }
        }
        return result;
    }

  
}
