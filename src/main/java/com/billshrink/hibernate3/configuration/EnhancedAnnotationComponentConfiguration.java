/**
 *
 * This is the MIT License
 * http://www.opensource.org/licenses/mit-license.php
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
 *
 */
package com.billshrink.hibernate3.configuration;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;

import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;
import javax.persistence.PersistenceException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Build;
import org.codehaus.mojo.hibernate3.configuration.AnnotationComponentConfiguration;
import org.hibernate.cfg.AnnotationConfiguration;
import org.hibernate.cfg.Configuration;
import org.hibernate.util.ReflectHelper;
import org.jboss.util.file.ArchiveBrowser;

/**
 * 
 * Added new filters <b>class-filter-exclude</b> and <b>class-filter-include</b>
 * which filters matching classes from being picked up or not for schema
 * manipulation. For example when working on certain projects you may not want
 * certain entities to be created in the database. For such cases you can use
 * the above filter and add the required regex to exclude those classes
 * 
 * In order to use this, in the hibernate3-maven-plugin configuration use
 * enhancedannotationconfiguration as the implementation and include this as the
 * dependency
 * 
 * <pre>
 * 	&lt;dependency&gt;
 * 		&lt;groupId&gt;com.billshrink.hibernate3&lt;/groupId&gt;
 * 		&lt;artifactId&gt;hibernate-configuration&lt;/artifactId&gt;
 * 		&lt;version&gt;1.1&lt;/version&gt;
 * 	&lt;/dependency&gt;
 * </pre>
 * 
 * Copied the source of AnnotationComponentConfiguration from
 * maven-hibernate3-jdk15
 * 
 * @author Saravana P Shanmugam
 * 
 */
public class EnhancedAnnotationComponentConfiguration extends AnnotationComponentConfiguration {

    /**
     * 
     */
    public EnhancedAnnotationComponentConfiguration() {
    }

    /*
     * (non-Jsdoc)
     * 
     * @see
     * org.codehaus.mojo.hibernate3.configuration.AnnotationComponentConfiguration
     * #createConfiguration()
     */
    @Override
    protected Configuration createConfiguration() {
        // retrievethe Build object
        Build build = getExporterMojo().getProject().getBuild();

        // now create an empty arraylist that is going to hold our entities
        List<String> entities = new ArrayList<String>();

        try {
            if (getExporterMojo().getComponentProperty("scan-classes", false)) {
                scanForClasses(new File(build.getOutputDirectory()), entities);
                scanForClasses(new File(build.getTestOutputDirectory()), entities);
            }

            if (getExporterMojo().getComponentProperty("scan-jars", false)) {
                @SuppressWarnings("unchecked")
                List<Artifact> runtimeArtifacts = getExporterMojo().getProject().getRuntimeArtifacts();
                for (Artifact a : runtimeArtifacts) {
                    File artifactFile = a.getFile();
                    if (!artifactFile.isDirectory()) {
                        getExporterMojo().getLog().debug("[URL] " + artifactFile.toURI().toURL().toString());
                        scanForClasses(artifactFile, entities);
                    }
                }

                @SuppressWarnings("unchecked")
                List<Artifact> testArtifacts = getExporterMojo().getProject().getTestArtifacts();
                for (Artifact a : testArtifacts) {
                    File artifactFile = a.getFile();
                    if (!artifactFile.isDirectory()) {
                        getExporterMojo().getLog().debug("[URL] " + artifactFile.toURI().toURL().toString());
                        scanForClasses(artifactFile, entities);
                    }
                }
            }
            String packages = getExporterMojo().getComponentProperty("add-packages", "");
            if (!"".equals(packages)) {
                entities.addAll(Arrays.asList(packages.split(",")));
            }
        } catch (MalformedURLException e) {
            getExporterMojo().getLog().error(e.getMessage(), e);
            return null;
        }

        // now create the configuration object
        AnnotationConfiguration configuration = new AnnotationConfiguration();
        addNamedAnnotatedClasses(configuration, entities);
        return configuration;
    }

    private void scanForClasses(File directory, List<String> entities) throws MalformedURLException {
        if (directory.list() != null) {
            String classFilterExclude = getExporterMojo().getComponentProperty("class-filter-exclude", null);
            Pattern classFilterExcludePattern = null == classFilterExclude || 0 == classFilterExclude.trim().length()
                    || "empty".equals(classFilterExclude) ? null : Pattern.compile(classFilterExclude,
                    Pattern.CASE_INSENSITIVE);
            String classFilterInclude = getExporterMojo().getComponentProperty("class-filter-include", null);
            Pattern classFilterIncludePattern = null == classFilterInclude || 0 == classFilterInclude.trim().length()
                    || "empty".equals(classFilterInclude) ? null : Pattern.compile(classFilterInclude,
                    Pattern.CASE_INSENSITIVE);
            if (null != classFilterExclude) {
                getExporterMojo().getLog().debug("using classFilterExclude " + classFilterExclude);
            }
            if (null != classFilterInclude) {
                getExporterMojo().getLog().debug("using classFilterInclude " + classFilterInclude);
            }
            getExporterMojo().getLog().debug("[scanForClasses] " + directory);
            URL jar = directory.toURI().toURL();
            Iterator<?> it;
            try {
                it = ArchiveBrowser.getBrowser(jar, new ArchiveBrowser.Filter() {
                    public boolean accept(String filename) {
                        return filename.endsWith(".class");
                    }
                });
            } catch (RuntimeException e) {
                throw new RuntimeException("error trying to scan <jar-file>: " + jar.toString(), e);
            }

            // need to look into every entry in the archive to see if anybody
            // has tags
            // defined.
            while (it.hasNext()) {
                InputStream stream = (InputStream) it.next();
                DataInputStream dstream = new DataInputStream(stream);
                ClassFile cf = null;
                try {
                    try {
                        cf = new ClassFile(dstream);
                    } finally {
                        dstream.close();
                        stream.close();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                AnnotationsAttribute visible = (AnnotationsAttribute) cf.getAttribute(AnnotationsAttribute.visibleTag);
                if (visible != null) {
                    if (null != classFilterExcludePattern && classFilterExcludePattern.matcher(cf.getName()).matches()) {
                        getExporterMojo().getLog().info("Ignoring (by excludes) " + cf.getName());
                        continue;
                    }
                    if (null != classFilterIncludePattern && !classFilterIncludePattern.matcher(cf.getName()).matches()) {
                        getExporterMojo().getLog().info("Ignoring (by includes) " + cf.getName());
                        continue;
                    }
                    boolean isEntity = visible.getAnnotation(Entity.class.getName()) != null;
                    if (isEntity) {
                        getExporterMojo().getLog().info("found EJB3 Entity bean: " + cf.getName());
                        entities.add(cf.getName());
                    }
                    boolean isEmbeddable = visible.getAnnotation(Embeddable.class.getName()) != null;
                    if (isEmbeddable) {
                        getExporterMojo().getLog().info("found EJB3 @Embeddable: " + cf.getName());
                        entities.add(cf.getName());
                    }
                    boolean isEmbeddableSuperclass = visible.getAnnotation(MappedSuperclass.class.getName()) != null;
                    if (isEmbeddableSuperclass) {
                        getExporterMojo().getLog().info("found EJB3 @MappedSuperclass: " + cf.getName());
                        entities.add(cf.getName());
                    }
                }
            }
        }
    }

    private void addNamedAnnotatedClasses(AnnotationConfiguration cfg, Collection<String> classNames) {
        for (String name : classNames) {
            try {
                Class<?> clazz = classForName(name);
                cfg.addAnnotatedClass(clazz);
            } catch (ClassNotFoundException cnfe) {
                Package pkg;
                try {
                    pkg = classForName(name + ".package-info").getPackage();
                } catch (ClassNotFoundException e) {
                    pkg = null;
                }
                if (pkg == null) {
                    throw new PersistenceException(name + " class or package not found", cnfe);
                } else {
                    cfg.addPackage(name);
                }
            }
        }
    }

    private Class<?> classForName(String className) throws ClassNotFoundException {
        return ReflectHelper.classForName(className, this.getClass());
    }

    /*
     * (non-Jsdoc)
     * 
     * @see
     * org.codehaus.mojo.hibernate3.configuration.AnnotationComponentConfiguration
     * #getName()
     */
    @Override
    public String getName() {
        return "enhancedannotationconfiguration";
    }

}
