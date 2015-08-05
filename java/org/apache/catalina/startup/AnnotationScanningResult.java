package org.apache.catalina.startup;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;

import org.apache.catalina.startup.ContextConfig.JavaClassCacheEntry;

class AnnotationScanningResult{
	Map<ServletContainerInitializer, Set<Class<?>>> taskInitializerClassMap = new LinkedHashMap<ServletContainerInitializer, Set<Class<?>>>();
	Map<String,JavaClassCacheEntry> taskJavaClasscache = new HashMap<String,JavaClassCacheEntry>();
	
	public AnnotationScanningResult(Map<ServletContainerInitializer, Set<Class<?>>> taskInitializerClassMap, Map<String,JavaClassCacheEntry> taskJavaClasscache){
		this.taskInitializerClassMap.putAll(taskInitializerClassMap);
		this.taskJavaClasscache.putAll(taskJavaClasscache);
	}

	public Map<ServletContainerInitializer, Set<Class<?>>> getTaskInitializerClassMap() {
		return taskInitializerClassMap;
	}

	public Map<String, JavaClassCacheEntry> getTaskJavaClasscache() {
		return taskJavaClasscache;
	}

}