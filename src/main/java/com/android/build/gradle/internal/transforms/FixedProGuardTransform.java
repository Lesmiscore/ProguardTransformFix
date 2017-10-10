package com.android.build.gradle.internal.transforms;

import com.android.annotations.*;
import com.android.build.api.transform.*;
import com.android.build.gradle.internal.pipeline.*;
import com.android.build.gradle.internal.scope.*;
import com.android.build.gradle.tasks.*;
import com.android.builder.tasks.*;
import com.google.common.base.*;
import com.google.common.collect.*;
import com.google.common.util.concurrent.*;
import proguard.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.*;
import java.util.zip.*;

import static com.android.builder.model.AndroidProject.*;
import static com.android.utils.FileUtils.*;
import static com.google.common.base.Preconditions.*;

public class FixedProGuardTransform  extends ProGuardTransform {

    private final VariantScope variantScope;

    private final File proguardOut;

    private final File printMapping;
    private final File dump;
    private final File printSeeds;
    private final File printUsage;
    private final ImmutableList<File> secondaryFileOutputs;

    private File testedMappingFile = null;
    private org.gradle.api.artifacts.Configuration testMappingConfiguration = null;

    private final File cacheDir;
    private final List<String> librariesWhichClassContains;

    public FixedProGuardTransform(@NonNull VariantScope variantScope, @NonNull File cacheDir, @Nullable List<String> librariesWhichClassContains) {
        super(variantScope);
        this.variantScope = variantScope;
        this.cacheDir = cacheDir;
        this.librariesWhichClassContains=
            librariesWhichClassContains==null?
                Collections.emptyList():
                librariesWhichClassContains;

        GlobalScope globalScope = variantScope.getGlobalScope();
        proguardOut = new File(Joiner.on(File.separatorChar).join(
            String.valueOf(globalScope.getBuildDir()),
            FD_OUTPUTS,
            "mapping",
            variantScope.getVariantConfiguration().getDirName()));

        printMapping = new File(proguardOut, "mapping.txt");
        dump = new File(proguardOut, "dump.txt");
        printSeeds = new File(proguardOut, "seeds.txt");
        printUsage = new File(proguardOut, "usage.txt");
        secondaryFileOutputs = ImmutableList.of(printMapping, dump, printSeeds, printUsage);
    }

    @Nullable
    public File getMappingFile() {
        return printMapping;
    }

    public void applyTestedMapping(@Nullable File testedMappingFile) {
        this.testedMappingFile = testedMappingFile;
    }

    public void applyTestedMapping(
        @Nullable org.gradle.api.artifacts.Configuration testMappingConfiguration) {
        this.testMappingConfiguration = testMappingConfiguration;
    }

    @NonNull
    @Override
    public String getName() {
        return "proguard";
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_JARS;
    }

    @NonNull
    @Override
    public Collection<SecondaryFile> getSecondaryFiles() {
        final List<SecondaryFile> files = Lists.newArrayList();

        if (testedMappingFile != null && testedMappingFile.isFile()) {
            files.add(SecondaryFile.nonIncremental(testedMappingFile));
        } else if (testMappingConfiguration != null) {
            files.add(SecondaryFile.nonIncremental(testMappingConfiguration));
        }

        // the config files
        files.addAll(getAllConfigurationFiles().stream()
            .map(SecondaryFile::nonIncremental)
            .collect(Collectors.toList()));

        return files;
    }

    @NonNull
    @Override
    public Collection<File> getSecondaryFileOutputs() {
        return secondaryFileOutputs;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void transform(@NonNull final TransformInvocation invocation) throws TransformException {
        // only run one minification at a time (across projects)
        SettableFuture<TransformOutputProvider> resultFuture = SettableFuture.create();
        final Job<Void> job = new Job<>(getName(),
            new com.android.builder.tasks.Task<Void>() {
                @Override
                public void run(@NonNull Job<Void> job,
                                @NonNull JobContext<Void> context) throws IOException {
                    doMinification(
                        invocation.getInputs(),
                        invocation.getReferencedInputs(),
                        invocation.getOutputProvider());
                }

                @Override
                public void finished() {
                    resultFuture.set(invocation.getOutputProvider());
                }

                @Override
                public void error(Exception e) {
                    resultFuture.setException(e);
                }
            }, resultFuture);
        try {
            SimpleWorkQueue.push(job);

            // wait for the task completion.
            try {
                job.awaitRethrowExceptions();
            } catch (ExecutionException e) {
                throw new RuntimeException("Job failed, see logs for details", e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private void doMinification(
        @NonNull Collection<TransformInput> inputs,
        @NonNull Collection<TransformInput> referencedInputs,
        @Nullable TransformOutputProvider output) throws IOException {
        checkNotNull(output, "Missing output object for transform " + getName());
        Set<QualifiedContent.ContentType> outputTypes = getOutputTypes();
        Set<QualifiedContent.Scope> scopes = getScopes();
        File outFile = output.getContentLocation("main", outputTypes, scopes, Format.JAR);
        mkdirs(outFile.getParentFile());
        File tempOutFile = new File(cacheDir,"temp.jar");
        mkdirs(tempOutFile.getParentFile());
        File tempMappingFile = new File(cacheDir,"mapping.txt");

        List<QualifiedContent> libraries;

        try {
            GlobalScope globalScope = variantScope.getGlobalScope();

            // set the mapping file if there is one.
            File testedMappingFile = computeMappingFile();
            if (testedMappingFile != null) {
                applyMapping(testedMappingFile);
            }

            // --- InJars / LibraryJars ---
            List<QualifiedContent> classes = Lists.newArrayList();
            classes.addAll(addInputsToConfiguration(inputs, false));
            addInputsToConfiguration(referencedInputs, true);

            // libraryJars: the runtime jars, with all optional libraries.
            for (File runtimeJar : globalScope.getAndroidBuilder().getBootClasspath(true)) {
                libraryJar(runtimeJar);
            }

            // --- Out files ---
            outJar(tempOutFile);

            // proguard doesn't verify that the seed/mapping/usage folders exist and will fail
            // if they don't so create them.
            mkdirs(proguardOut);

            for (File configFile : getAllConfigurationFiles()) {
                applyConfigurationFile(configFile);
            }
            keep("class **.R { *; }");
            keep("class **.R$** { *; }");

            configuration.printMapping = tempMappingFile;
            configuration.dump = dump;
            configuration.printSeeds = printSeeds;
            configuration.printUsage = printUsage;

            forceprocessing();
            runProguard();

            libraries=classes.stream()
                .filter(a->a.getFile().isFile())
                .filter(f-> !f.getFile().equals(tempOutFile))
                .collect(Collectors.toList());
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }

            throw new IOException(e);
        }

        try {
            /* Clear everything here */
            Arrays.stream(Configuration.class.getDeclaredFields())
                .filter(a->Collection.class.isAssignableFrom(a.getType()))
                .map(a->getField(a,configuration))
                .map(a->(Collection)a)
                .filter(Objects::nonNull)
                .forEach(Collection::clear);
            Arrays.stream(Configuration.class.getDeclaredFields())
                .filter(a->ClassPath.class==a.getType())
                .map(a->getField(a,configuration))
                .map(a->(ClassPath)a)
                .filter(Objects::nonNull)
                .forEach(ClassPath::clear);

            GlobalScope globalScope = variantScope.getGlobalScope();

            // set the mapping file if there is one.
            File testedMappingFile = computeMappingFile();
            if (testedMappingFile != null) {
                applyMapping(testedMappingFile);
            }
            applyMapping(tempMappingFile);

            // --- InJars / LibraryJars ---
            inJar(tempOutFile);
            for(QualifiedContent lib:libraries) {
                handleQualifiedContentBase(configuration.programJars,lib,null);
            }
            addInputsToConfiguration(referencedInputs, true);

            // libraryJars: the runtime jars, with all optional libraries.
            for (File runtimeJar : globalScope.getAndroidBuilder().getBootClasspath(true)) {
                libraryJar(runtimeJar);
            }

            // --- Out files ---
            outJar(outFile);

            // proguard doesn't verify that the seed/mapping/usage folders exist and will fail
            // if they don't so create them.
            mkdirs(proguardOut);

            for (File configFile : getAllConfigurationFiles()) {
                applyConfigurationFile(configFile);
            }

            configuration.printMapping = printMapping;
            configuration.dump = dump;
            configuration.printSeeds = printSeeds;
            configuration.printUsage = printUsage;

            /* We can't do that here */
            dontoptimize();

            forceprocessing();
            runProguard();
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }

            throw new IOException(e);
        }
    }

    private List<QualifiedContent> addInputsToConfiguration(
        @NonNull Collection<TransformInput> inputs,
        boolean referencedOnly) {
        ClassPath classPath;
        List<String> baseFilter;
        List<QualifiedContent> inputFiles = Lists.newArrayList();

        if (referencedOnly) {
            classPath = configuration.libraryJars;
            baseFilter = JAR_FILTER;
        } else {
            classPath = configuration.programJars;
            baseFilter = null;
        }

        for (TransformInput transformInput : inputs) {
            for (JarInput jarInput : transformInput.getJarInputs()) {
                if(handleQualifiedContent(classPath, jarInput, baseFilter))
                    inputFiles.add(jarInput);
            }

            for (DirectoryInput directoryInput : transformInput.getDirectoryInputs()) {
                if(handleQualifiedContent(classPath, directoryInput, baseFilter))
                    inputFiles.add(directoryInput);
            }
        }

        return inputFiles;
    }

    private boolean handleQualifiedContent(
        @NonNull ClassPath classPath,
        @NonNull QualifiedContent content,
        @Nullable List<String> baseFilter) {
        List<String> filter = baseFilter;

        if (!content.getContentTypes().contains(QualifiedContent.DefaultContentType.CLASSES)) {
            // if the content is not meant to contain classes, we ignore them
            // in case they are present.
            ImmutableList.Builder<String> builder = ImmutableList.builder();
            if (filter != null) {
                builder.addAll(filter);
            }
            builder.add("!**.class");
            filter = builder.build();
        } else if (!content.getContentTypes().contains(QualifiedContent.DefaultContentType.RESOURCES)) {
            // if the content is not meant to contain resources, we ignore them
            // in case they are present (by accepting only classes.)
            filter = ImmutableList.of("**.class");
        }

        if (content.getFile().isDirectory() || containsOneOfClasses(content.getFile())) {
            inputJar(classPath, content.getFile(), filter);
            return false;
        } else {
            inputJar(configuration.libraryJars, content.getFile(), filter);
            return true;
        }
    }

    private void handleQualifiedContentBase(
        @NonNull ClassPath classPath,
        @NonNull QualifiedContent content,
        @Nullable List<String> baseFilter) {
        List<String> filter = baseFilter;

        if (!content.getContentTypes().contains(QualifiedContent.DefaultContentType.CLASSES)) {
            // if the content is not meant to contain classes, we ignore them
            // in case they are present.
            ImmutableList.Builder<String> builder = ImmutableList.builder();
            if (filter != null) {
                builder.addAll(filter);
            }
            builder.add("!**.class");
            filter = builder.build();
        } else if (!content.getContentTypes().contains(QualifiedContent.DefaultContentType.RESOURCES)) {
            // if the content is not meant to contain resources, we ignore them
            // in case they are present (by accepting only classes.)
            filter = ImmutableList.of("**.class");
        }

        inputJar(classPath, content.getFile(), filter);
    }

    @Nullable
    private File computeMappingFile() {
        if (testedMappingFile != null && testedMappingFile.isFile()) {
            return testedMappingFile;
        } else if (testMappingConfiguration != null && testMappingConfiguration.getSingleFile().isFile()) {
            return testMappingConfiguration.getSingleFile();
        }


        return null;
    }

    private Object getField(Field f,Object o){
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private boolean containsOneOfClasses(File jarOrDir){
        if(jarOrDir.isDirectory()){
            for(String fqdn:librariesWhichClassContains){
                String relPath=fqdn.replace('.','/')+".class";
                if(new File(jarOrDir,relPath).exists()){
                    return true;
                }
            }
        }else if(jarOrDir.isFile()){
            try(ZipFile zf=new ZipFile(jarOrDir)){
                return Collections.list(zf.entries()).stream()
                    .map(ZipEntry::getName)
                    .filter(a->a.endsWith(".class"))
                    .map(a->a.substring(0,a.length()-6))
                    .map(a->a.replace('/','.'))
                    .anyMatch(librariesWhichClassContains::contains);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    public static void injectProGuardTransform(TransformTask task) throws Throwable{
        injectProGuardTransform(task,null);
    }

    public static void injectProGuardTransform(TransformTask task,List<String> librariesWhichClassContains) throws Throwable{
        Transform transform=task.getTransform();
        if (!(transform instanceof ProGuardTransform) || transform instanceof FixedProGuardTransform) {
            return;
        }
        ProGuardTransform pgt=(ProGuardTransform)transform;

        Field variantScopeField=ProGuardTransform.class.getDeclaredField("variantScope");
        variantScopeField.setAccessible(true);
        VariantScope scope= (VariantScope) variantScopeField.get(pgt);

        FixedProGuardTransform fixedTransform=new FixedProGuardTransform(scope,task.getTemporaryDir(),librariesWhichClassContains);
        fixedTransform.setConfigurationFiles(pgt::getAllConfigurationFiles);
        /* Because transform field is not final, this code could run well */
        Field transformField=TransformTask.class.getDeclaredField("transform");
        transformField.setAccessible(true);
        transformField.set(task,fixedTransform);
    }
}
