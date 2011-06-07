/*
 * The MIT License
 * 
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Alan Harder
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.util;

import com.google.common.collect.ImmutableMap;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.mapper.AnnotationMapper;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.ConverterMatcher;
import com.thoughtworks.xstream.converters.DataHolder;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.converters.SingleValueConverterWrapper;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.io.HierarchicalStreamDriver;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.CannotResolveClassException;
import hudson.diagnosis.OldDataMonitor;
import jenkins.model.Jenkins;
import hudson.model.Label;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.util.xstream.ImmutableMapConverter;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link XStream} enhanced for additional Java5 support and improved robustness.
 * @author Kohsuke Kawaguchi
 */
public class XStream2 extends XStream {
    private Converter reflectionConverter;
    private ThreadLocal<Boolean> oldData = new ThreadLocal<Boolean>();

    private final Map<String,Class> compatibilityAliases = new ConcurrentHashMap<String, Class>();

    public XStream2() {
        init();
    }

    public XStream2(HierarchicalStreamDriver hierarchicalStreamDriver) {
        super(hierarchicalStreamDriver);
        init();
    }

    @Override
    public Object unmarshal(HierarchicalStreamReader reader, Object root, DataHolder dataHolder) {
        // init() is too early to do this
        // defensive because some use of XStream happens before plugins are initialized.
        Jenkins h = Jenkins.getInstance();
        if(h!=null && h.pluginManager!=null && h.pluginManager.uberClassLoader!=null) {
            setClassLoader(h.pluginManager.uberClassLoader);
        }

        Object o = super.unmarshal(reader,root,dataHolder);
        if (oldData.get()!=null) {
            oldData.remove();
            if (o instanceof Saveable) OldDataMonitor.report((Saveable)o, "1.106");
        }
        return o;
    }

    @Override
    protected Converter createDefaultConverter() {
        // replace default reflection converter
        reflectionConverter = new RobustReflectionConverter(getMapper(),new JVM().bestReflectionProvider());
        return reflectionConverter;
    }

    private void init() {
        // list up types that should be marshalled out like a value, without referencial integrity tracking.
        addImmutableType(Result.class);

        registerConverter(new RobustCollectionConverter(getMapper(),getReflectionProvider()),10);
        registerConverter(new ImmutableMapConverter(getMapper(),getReflectionProvider()),10);
        registerConverter(new ConcurrentHashMapConverter(getMapper(),getReflectionProvider()),10);
        registerConverter(new CopyOnWriteMap.Tree.ConverterImpl(getMapper()),10); // needs to override MapConverter
        registerConverter(new DescribableList.ConverterImpl(getMapper()),10); // explicitly added to handle subtypes
        registerConverter(new Label.ConverterImpl(),10);

        // this should come after all the XStream's default simpler converters,
        // but before reflection-based one kicks in.
        registerConverter(new AssociatedConverterImpl(this),-10);
    }

    @Override
    protected MapperWrapper wrapMapper(MapperWrapper next) {
        Mapper m = new CompatibilityMapper(new MapperWrapper(next) {
            @Override
            public String serializedClass(Class type) {
                if (type != null && ImmutableMap.class.isAssignableFrom(type))
                    return super.serializedClass(ImmutableMap.class);
                else
                    return super.serializedClass(type);
            }
        });
        AnnotationMapper a = new AnnotationMapper(m, getConverterRegistry(), getClassLoader(), getReflectionProvider(), getJvm());
        a.autodetectAnnotations(true);
        return a;
    }

    /**
     * Adds an alias in case class names change.
     *
     * Unlike {@link #alias(String, Class)}, which uses the registered alias name for writing XML,
     * this method registers an alias to be used only for the sake of reading from XML. This makes
     * this method usable for the situation when class names change.
     *
     * @param oldClassName
     *      Fully qualified name of the old class name.
     * @param newClass
     *      New class that's field-compatible with the given old class name.
     * @since 1.416
     */
    public void addCompatibilityAlias(String oldClassName, Class newClass) {
        compatibilityAliases.put(oldClassName,newClass);
    }

    /**
     * Prior to Hudson 1.106, XStream 1.1.x was used which encoded "$" in class names
     * as "-" instead of "_-" that is used now.  Up through Hudson 1.348 compatibility
     * for old serialized data was maintained via {@code XStream11XmlFriendlyMapper}.
     * However, it was found (HUDSON-5768) that this caused fields with "__" to fail
     * deserialization due to double decoding.  Now this class is used for compatibility.
     */
    private class CompatibilityMapper extends MapperWrapper {
        private CompatibilityMapper(Mapper wrapped) {
            super(wrapped);
        }

        @Override
        public Class realClass(String elementName) {
            Class s = compatibilityAliases.get(elementName);
            if (s!=null)    return s;

            try {
                return super.realClass(elementName);
            } catch (CannotResolveClassException e) {
                // If a "-" is found, retry with mapping this to "$"
                if (elementName.indexOf('-') >= 0) try {
                    Class c = super.realClass(elementName.replace('-', '$'));
                    oldData.set(Boolean.TRUE);
                    return c;
                } catch (CannotResolveClassException e2) { }
                // Throw original exception
                throw e;
            }
        }
    }

    /**
     * If a class defines a nested {@code ConverterImpl} subclass, use that as a {@link Converter}.
     * Its constructor may have XStream/XStream2 and/or Mapper parameters (or no params).
     */
    private static final class AssociatedConverterImpl implements Converter {
        private final XStream xstream;
        private final ConcurrentHashMap<Class,Converter> cache =
                new ConcurrentHashMap<Class,Converter>();

        private AssociatedConverterImpl(XStream xstream) {
            this.xstream = xstream;
        }

        private Converter findConverter(Class t) {
            Converter result = cache.get(t);
            if (result != null)
                // ConcurrentHashMap does not allow null, so use this object to represent null
                return result == this ? null : result;
            try {
                if(t==null || t.getClassLoader()==null)
                    return null;
                Class<?> cl = t.getClassLoader().loadClass(t.getName() + "$ConverterImpl");
                Constructor<?> c = cl.getConstructors()[0];

                Class<?>[] p = c.getParameterTypes();
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    if(p[i]==XStream.class || p[i]==XStream2.class)
                        args[i] = xstream;
                    else if(p[i]== Mapper.class)
                        args[i] = xstream.getMapper();
                    else
                        throw new InstantiationError("Unrecognized constructor parameter: "+p[i]);

                }
                ConverterMatcher cm = (ConverterMatcher)c.newInstance(args);
                result = cm instanceof SingleValueConverter
                        ? new SingleValueConverterWrapper((SingleValueConverter)cm)
                        : (Converter)cm;
                cache.put(t, result);
                return result;
            } catch (ClassNotFoundException e) {
                cache.put(t, this);  // See above.. this object in cache represents null
                return null;
            } catch (IllegalAccessException e) {
                IllegalAccessError x = new IllegalAccessError();
                x.initCause(e);
                throw x;
            } catch (InstantiationException e) {
                InstantiationError x = new InstantiationError();
                x.initCause(e);
                throw x;
            } catch (InvocationTargetException e) {
                InstantiationError x = new InstantiationError();
                x.initCause(e);
                throw x;
            }
        }

        public boolean canConvert(Class type) {
            return findConverter(type)!=null;
        }

        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            findConverter(source.getClass()).marshal(source,writer,context);
        }

        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            return findConverter(context.getRequiredType()).unmarshal(reader,context);
        }
    }

    /**
     * Create a nested {@code ConverterImpl} subclass that extends this class to run some
     * callback code just after a type is unmarshalled by RobustReflectionConverter.
     * Example: <pre> public static class ConverterImpl extends XStream2.PassthruConverter&lt;MyType&gt; {
     *   public ConverterImpl(XStream2 xstream) { super(xstream); }
     *   @Override protected void callback(MyType obj, UnmarshallingContext context) {
     *     ...
     * </pre>
     */
    public static abstract class PassthruConverter<T> implements Converter {
        private Converter converter;

        public PassthruConverter(XStream2 xstream) {
            converter = xstream.reflectionConverter;
        }

        public boolean canConvert(Class type) {
            // marshal/unmarshal called directly from AssociatedConverterImpl
            return false;
        }

        public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
            converter.marshal(source, writer, context);
        }

        public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
            Object obj = converter.unmarshal(reader, context);
            callback((T)obj, context);
            return obj;
        }

        protected abstract void callback(T obj, UnmarshallingContext context);
    }
}
