// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2023 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.buildserver.tasks.android;

import com.google.appinventor.buildserver.BuildType;
import com.google.appinventor.buildserver.TaskResult;
import com.google.appinventor.buildserver.context.AndroidCompilerContext;
import com.google.appinventor.buildserver.interfaces.AndroidTask;
import com.google.appinventor.buildserver.util.Execution;
import com.google.appinventor.buildserver.util.ExecutorUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@BuildType(aab = true, apk = true)
public class RunD8 extends DexTask implements AndroidTask {
  private static final boolean USE_D8_PROGUARD_RULES = true;

  @Override
  public TaskResult execute(AndroidCompilerContext context) {
    Set<String> mainDexClasses = new HashSet<>();
    final List<File> inputs = new ArrayList<>();
    try {
      recordForMainDex(context.getPaths().getClassesDir(), mainDexClasses);
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getSimpleAndroidRuntimeJar()), mainDexClasses)));
      inputs.add(preDexLibrary(context, recordForMainDex(
          new File(context.getResources().getKawaRuntime()), mainDexClasses)));

      final Set<String> criticalJars = getCriticalJars(context);

      for (String jar : criticalJars) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResource(jar)), mainDexClasses)));
      }

      // Only include ACRA for the companion app
      if (context.isForCompanion()) {
        inputs.add(preDexLibrary(context, recordForMainDex(
            new File(context.getResources().getAcraRuntime()), mainDexClasses)));
      }

      for (String jar : context.getResources().getSupportJars()) {
        if (criticalJars.contains(jar)) {  // already covered above
          continue;
        }
        inputs.add(preDexLibrary(context, new File(context.getResource(jar))));
      }

      // Add the rest of the libraries in any order
      for (String lib : context.getComponentInfo().getUniqueLibsNeeded()) {
        inputs.add(preDexLibrary(context, new File(lib)));
      }

      // Add extension libraries
      Set<String> addedExtJars = new HashSet<>();
      for (String type : context.getExtCompTypes()) {
        String sourcePath = ExecutorUtils.getExtCompDirPath(type, context.getProject(),
            context.getExtTypePathCache())
            + context.getResources().getSimpleAndroidRuntimeJarPath();
        if (!addedExtJars.contains(sourcePath)) {
          inputs.add(new File(sourcePath));
          addedExtJars.add(sourcePath);
        }
      }

      Files.walkFileTree(context.getPaths().getClassesDir().toPath(), new FileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir,
            BasicFileAttributes attrs) {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file,
            BasicFileAttributes attrs) {
          if (file.toString().endsWith(".class")) {
            inputs.add(file.toFile());
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
          return FileVisitResult.CONTINUE;
        }
      });

      if (USE_D8_PROGUARD_RULES) {
        // Google is moving to proguard-style rules for computing the main dex in d8
        mainDexClasses.clear();
        mainDexClasses.add("com.google.appinventor.components.runtime.ReplApplication");
        mainDexClasses.add(context.getProject().getMainClass());
      }

      // Run the final DX step to include user's compiled screens
      if (!runD8(context, inputs, mainDexClasses)) {
        return TaskResult.generateError("d8 failed.");
      }

      // Aggregate all classes.dex files output by dx
      File[] files = context.getPaths().getTmpDir().listFiles((dir, name) -> name.endsWith(".dex"));
      if (files == null) {
        throw new FileNotFoundException("Could not find classes.dex");
      }
      Collections.addAll(context.getResources().getDexFiles(), files);
      return TaskResult.generateSuccess();
    } catch (IOException e) {
      return TaskResult.generateError(e);
    }
  }

  /**
   * Runs Android SDK's d8 program to create a dex file for the given collection of inputs. The
   * classes.dex file(s) will be output to the active context's build directory.
   *
   * @param context the build context
   * @param inputs collection of input files. For a complete list of supported input types see
   *               <a href="https://developer.android.com/tools/d8">d8</a>.
   * @return true if the process succeeded
   * @throws IOException if the dex file is unable to be moved to the {@code intermediateFileName}
   */
  private static boolean runD8(AndroidCompilerContext context, Collection<File> inputs,
      Set<String> mainDexClasses) throws IOException {
    return runD8(context, inputs, mainDexClasses, context.getPaths().getTmpDir().getAbsolutePath(),
        null);
  }

  /**
   * Run Android SDK's d8 program to create a dex file for the given collection of inputs.
   *
   * @param context the build context
   * @param inputs collection of input files. For a complete list of supported input types see
   *               <a href="https://developer.android.com/tools/d8">d8</a>.
   * @param outputDir the destination for the classes.dex file
   * @param intermediateFileName an alternative name to use for the dex file when pre-dexing
   * @return true if the process succeeded
   * @throws IOException if the dex file is unable to be moved to the {@code intermediateFileName}
   */
  private static boolean runD8(AndroidCompilerContext context, Collection<File> inputs,
      Set<String> mainDexClasses, String outputDir, String intermediateFileName)
      throws IOException {
    List<String> arguments = new ArrayList<>();
    arguments.add("java");
    arguments.add("-Xmx" + context.getChildProcessRam() + "M");
    arguments.add("-Xss8m");
    arguments.add("-cp");
    arguments.add(context.getResources().getD8Jar());
    arguments.add("com.android.tools.r8.D8");
    if (intermediateFileName != null) {
      arguments.add("--intermediate");
    }
    arguments.add("--lib");
    arguments.add(context.getResources().getAndroidRuntime());
    if (intermediateFileName == null) {
      arguments.add("--classpath");
      arguments.add(context.getPaths().getClassesDir().getAbsolutePath());
    }
    arguments.add("--output");
    arguments.add(outputDir);
    arguments.add("--min-api");
    arguments.add(Integer.toString(AndroidBuildUtils.computeMinSdk(context)));
    if (mainDexClasses != null) {
      if (USE_D8_PROGUARD_RULES) {
        arguments.add("--main-dex-rules");
        arguments.add(writeClassRules(context.getPaths().getClassesDir(), mainDexClasses));
      } else {
        arguments.add("--main-dex-list");
        arguments.add(writeClassList(context.getPaths().getClassesDir(), mainDexClasses));
      }
    }
    for (File input : inputs) {
      arguments.add(input.getAbsolutePath());
    }
    synchronized (context.getResources().getSyncKawaOrDx()) {
      boolean result = Execution.execute(context.getPaths().getTmpDir(),
          arguments.toArray(new String[0]), System.out, System.err);
      if (!result) {
        return false;
      }
    }
    if (intermediateFileName != null) {
      Files.move(FileSystems.getDefault().getPath(outputDir, "classes.dex"),
          FileSystems.getDefault().getPath(outputDir, intermediateFileName));
    }
    return true;
  }

  /**
   * Dex the given {@code input} file and cache the results.
   *
   * @param context the build context
   * @param input the input JAR file
   * @return the path of the library to use as an input to the downstream d8 process
   * @throws IOException if the d8 process fails due to an I/O issue
   */
  private static File preDexLibrary(AndroidCompilerContext context, File input) throws IOException {
    synchronized (PREDEX_CACHE) {
      File cacheDir = new File(context.getDexCacheDir());
      File dexedLib = getDexFileName(input, cacheDir);
      if (dexedLib.isFile()) {
        context.getReporter().info(String.format("Using pre-dexed %1$s <- %2$s",
            dexedLib.getName(), input));
      } else {
        boolean success = runD8(context, Collections.singleton(input), null,
            context.getDexCacheDir(), dexedLib.getName());
        if (!success) {
          return input;
        }
      }
      return dexedLib;
    }
  }
}
