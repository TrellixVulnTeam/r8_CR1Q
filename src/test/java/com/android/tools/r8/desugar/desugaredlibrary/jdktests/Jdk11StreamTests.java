// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.desugaredlibrary.jdktests;

import static com.android.tools.r8.ToolHelper.JDK_TESTS_BUILD_DIR;
import static com.android.tools.r8.utils.FileUtils.CLASS_EXTENSION;
import static com.android.tools.r8.utils.FileUtils.JAVA_EXTENSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tools.r8.D8TestCompileResult;
import com.android.tools.r8.D8TestRunResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestRuntime;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class Jdk11StreamTests extends Jdk11DesugaredLibraryTestBase {

  private final boolean useCf2Cf;
  private final TestParameters parameters;
  private final boolean shrinkDesugaredLibrary;

  @Parameters(name = "{2}, shrinkDesugaredLibrary: {0}, useCf2Cf: {1}")
  public static List<Object[]> data() {
    // TODO(134732760): Support Dalvik VMs, currently fails because libjavacrypto is required and
    // present only in ART runtimes.
    return buildParameters(
        BooleanUtils.values(),
        BooleanUtils.values(),
        getTestParameters()
            .withDexRuntimesStartingFromIncluding(Version.V5_1_1)
            .withAllApiLevels()
            .build());
  }

  public Jdk11StreamTests(
      boolean shrinkDesugaredLibrary, boolean useCf2Cf, TestParameters parameters) {
    this.shrinkDesugaredLibrary = shrinkDesugaredLibrary;
    this.useCf2Cf = useCf2Cf;
    this.parameters = parameters;
  }

  private static Path JDK_11_STREAM_TEST_CLASSES_DIR;
  private static final Path JDK_11_STREAM_TEST_FILES_DIR =
      Paths.get("third_party/openjdk/jdk-11-test/java/util/stream/test");
  private static Path[] JDK_11_STREAM_TEST_COMPILED_FILES;

  private static Path[] getJdk11StreamTestFiles() throws Exception {
    Path[] files = getAllFilesWithSuffixInDirectory(JDK_11_STREAM_TEST_FILES_DIR, JAVA_EXTENSION);
    assert files.length > 0;
    return files;
  }

  private static final String[] FAILING_RUNNABLE_TESTS = new String[] {
        // Disabled, D8 generated code raises AbstractMethodError instead of NPE because of API
        // unsupported in the desugared library.
        // "org/openjdk/tests/java/util/stream/SpliteratorTest.java",
        // Disabled because both the stream close issue and the Random issue (See below).
        // "org/openjdk/tests/java/util/stream/LongPrimitiveOpsTests.java",
        // Disabled because explicit cast done on a wrapped value.
        // "org/openjdk/tests/java/util/SplittableRandomTest.java",
        // Disabled due to a desugaring failure due to the extended library used for the test.
        // "org/openjdk/tests/java/util/stream/IterateTest.java",
      };

  // Cannot succeed with JDK 8 desugared library because use J9 features.
  // Stream close issue with try with resource desugaring mixed with partial library desugaring.
  public static final String[] STREAM_CLOSE_TESTS =
      new String[] {"org/openjdk/tests/java/util/stream/StreamCloseTest.java"};

  // Cannot succeed with JDK 8 desugared library because use J9 features.
  public static final String[] SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_AND_V7 =
      new String[] {
        // Require the virtual method isDefault() in class java/lang/reflect/Method.
        "org/openjdk/tests/java/util/stream/WhileOpTest.java",
        "org/openjdk/tests/java/util/stream/WhileOpStatefulTest.java",
        // Require a Random method not present before Android 7 and not desugared.
        "org/openjdk/tests/java/util/stream/IntPrimitiveOpsTests.java"
      };

  // Disabled because time to run > 1 min for each test.
  // Can be used for experimentation/testing purposes.
  private static String[] LONG_RUNNING_TESTS =
      new String[] {
        "org/openjdk/tests/java/util/stream/InfiniteStreamWithLimitOpTest.java",
        "org/openjdk/tests/java/util/stream/CountLargeTest.java",
        "org/openjdk/tests/java/util/stream/RangeTest.java",
        "org/openjdk/tests/java/util/stream/CollectorsTest.java",
        "org/openjdk/tests/java/util/stream/FlatMapOpTest.java",
        "org/openjdk/tests/java/util/stream/StreamSpliteratorTest.java",
        "org/openjdk/tests/java/util/stream/StreamLinkTest.java",
        "org/openjdk/tests/java/util/stream/StreamBuilderTest.java",
        "org/openjdk/tests/java/util/stream/SliceOpTest.java",
        "org/openjdk/tests/java/util/stream/ToArrayOpTest.java"
      };

  private static final String[] SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_ONLY =
      new String[] {
        // Assertion error
        "org/openjdk/tests/java/util/stream/CollectAndSummaryStatisticsTest.java",
        "org/openjdk/tests/java/util/stream/CountTest.java",
        // J9 Random problem
        "org/openjdk/tests/java/util/stream/DoublePrimitiveOpsTests.java",
      };

  private static String[] SUCCESSFUL_RUNNABLE_TESTS =
      new String[] {
        "org/openjdk/tests/java/util/stream/FindFirstOpTest.java",
        "org/openjdk/tests/java/util/stream/MapOpTest.java",
        "org/openjdk/tests/java/util/stream/DistinctOpTest.java",
        "org/openjdk/tests/java/util/MapTest.java",
        "org/openjdk/tests/java/util/FillableStringTest.java",
        "org/openjdk/tests/java/util/stream/ForEachOpTest.java",
        "org/openjdk/tests/java/util/stream/CollectionAndMapModifyStreamTest.java",
        "org/openjdk/tests/java/util/stream/GroupByOpTest.java",
        "org/openjdk/tests/java/util/stream/PrimitiveAverageOpTest.java",
        "org/openjdk/tests/java/util/stream/TeeOpTest.java",
        "org/openjdk/tests/java/util/stream/MinMaxTest.java",
        "org/openjdk/tests/java/util/stream/ConcatTest.java",
        "org/openjdk/tests/java/util/stream/StreamParSeqTest.java",
        "org/openjdk/tests/java/util/stream/ReduceByOpTest.java",
        "org/openjdk/tests/java/util/stream/ConcatOpTest.java",
        "org/openjdk/tests/java/util/stream/IntReduceTest.java",
        "org/openjdk/tests/java/util/stream/SortedOpTest.java",
        "org/openjdk/tests/java/util/stream/MatchOpTest.java",
        "org/openjdk/tests/java/util/stream/IntSliceOpTest.java",
        "org/openjdk/tests/java/util/stream/SequentialOpTest.java",
        "org/openjdk/tests/java/util/stream/PrimitiveSumTest.java",
        "org/openjdk/tests/java/util/stream/ReduceTest.java",
        "org/openjdk/tests/java/util/stream/IntUniqOpTest.java",
        "org/openjdk/tests/java/util/stream/FindAnyOpTest.java"
      };

  private boolean streamCloseTestShouldSucceed() {
    if (!isJDK11DesugaredLibrary()) {
      return false;
    }
    // TODO(b/216047740): Investigate if this runs on Dalvik VMs.
    // StreamCloseTest relies on suppressed exceptions which may not work on Dalvik VMs.
    return parameters.getDexRuntimeVersion().isNewerThan(Version.V4_4_4);
  }

  private Map<String, String> getSuccessfulTests() {
    Map<String, String> runnableTests = getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS);
    if (isJDK11DesugaredLibrary()) {
      runnableTests.putAll(getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_ONLY));
      if (parameters.getDexRuntimeVersion().isNewerThanOrEqual(Version.V7_0_0)) {
        runnableTests.putAll(getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_AND_V7));
      }
    }
    if (streamCloseTestShouldSucceed()) {
      runnableTests.putAll(getRunnableTests(STREAM_CLOSE_TESTS));
    }
    return runnableTests;
  }

  private Map<String, String> getFailingTests() {
    Map<String, String> runnableTests = getRunnableTests(FAILING_RUNNABLE_TESTS);
    if (!isJDK11DesugaredLibrary()) {
      runnableTests.putAll(getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_ONLY));
      runnableTests.putAll(getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_AND_V7));
    } else if (!parameters.getDexRuntimeVersion().isNewerThanOrEqual(Version.V7_0_0)) {
      runnableTests.putAll(getRunnableTests(SUCCESSFUL_RUNNABLE_TESTS_ON_JDK11_AND_V7));
    }
    if (!streamCloseTestShouldSucceed()) {
      runnableTests.putAll(getRunnableTests(STREAM_CLOSE_TESTS));
    }
    return runnableTests;
  }

  private static Map<String, String> getRunnableTests(String[] tests) {
    IdentityHashMap<String, String> pathToName = new IdentityHashMap<>();
    int javaExtSize = JAVA_EXTENSION.length();
    for (String runnableTest : tests) {
      String nameWithoutJavaExt = runnableTest.substring(0, runnableTest.length() - javaExtSize);
      pathToName.put(
          JDK_11_STREAM_TEST_CLASSES_DIR + "/" + nameWithoutJavaExt + CLASS_EXTENSION,
          nameWithoutJavaExt.replace("/", "."));
    }
    return pathToName;
  }

  private static String[] missingDesugaredMethods() {
    // These methods are from Java 9 and not supported in the current desugared libraries.
    return new String[] {
      // Stream
      "takeWhile(",
      "dropWhile(",
      "iterate(",
      "range(",
      "doubles(",
      // Collectors
      "filtering(",
      "flatMapping(",
      // isDefault()Z in class Ljava/lang/reflect/Method
      "isDefault("
    };
  }

  @BeforeClass
  public static void compileJdk11StreamTests() throws Exception {
    JDK_11_STREAM_TEST_CLASSES_DIR = getStaticTemp().newFolder("stream").toPath();
    List<String> options =
        Arrays.asList(
            "--add-reads",
            "java.base=ALL-UNNAMED",
            "--patch-module",
            "java.base=" + JDK_11_JAVA_BASE_EXTENSION_CLASSES_DIR);
    javac(TestRuntime.getCheckedInJdk11(), getStaticTemp())
        .addOptions(options)
        .addClasspathFiles(
            Collections.singletonList(Paths.get(JDK_TESTS_BUILD_DIR + "testng-6.10.jar")))
        .addSourceFiles(getJdk11StreamTestFiles())
        .setOutputPath(JDK_11_STREAM_TEST_CLASSES_DIR)
        .compile();
    JDK_11_STREAM_TEST_COMPILED_FILES =
        getAllFilesWithSuffixInDirectory(JDK_11_STREAM_TEST_CLASSES_DIR, CLASS_EXTENSION);
    assert JDK_11_STREAM_TEST_COMPILED_FILES.length > 0;
  }

  @Test
  public void testStream() throws Exception {
    Assume.assumeFalse(
        "getAllFilesWithSuffixInDirectory() seems to find different files on Windows",
        ToolHelper.isWindows());
    Assume.assumeTrue(
        "Requires Java base extensions, should add it when not desugaring",
        parameters.getApiLevel().getLevel() < AndroidApiLevel.N.getLevel());

    D8TestCompileResult compileResult = compileStreamTestsToDex(useCf2Cf);
    runSuccessfulTests(compileResult);
    runFailingTests(compileResult);
  }

  private D8TestCompileResult compileStreamTestsToDex(boolean cf2cf) throws Exception {
    KeepRuleConsumer keepRuleConsumer = createKeepRuleConsumer(parameters);
    List<Path> filesToCompile =
        Arrays.stream(JDK_11_STREAM_TEST_COMPILED_FILES)
            .filter(file -> !file.toString().contains("lang/invoke"))
            .collect(Collectors.toList());

    if (cf2cf) {
      Path jar =
          testForD8(Backend.CF)
              .addProgramFiles(filesToCompile)
              .addProgramFiles(getPathsFiles())
              .addProgramFiles(getSafeVarArgsFile())
              .addProgramFiles(testNGSupportProgramFiles())
              .addOptionsModification(opt -> opt.testing.trackDesugaredAPIConversions = true)
              .addLibraryFiles(getLibraryFile())
              .addLibraryFiles(JDK_11_JAVA_BASE_EXTENSION_CLASSES_DIR)
              .setMinApi(parameters.getApiLevel())
              .enableCoreLibraryDesugaring(parameters.getApiLevel(), keepRuleConsumer)
              .allowStdoutMessages()
              .compile()
              .writeToZip();
      // Collection keep rules is only implemented in the DEX writer.
      String desugaredLibraryKeepRules = keepRuleConsumer.get();
      if (desugaredLibraryKeepRules != null) {
        assertEquals(0, desugaredLibraryKeepRules.length());
        desugaredLibraryKeepRules = "-keep class * { *; }";
      }
      return testForD8()
          .addProgramFiles(jar)
          .setMinApi(parameters.getApiLevel())
          .disableDesugaring()
          .compile()
          .addDesugaredCoreLibraryRunClassPath(
              this::buildDesugaredLibraryWithJavaBaseExtension,
              parameters.getApiLevel(),
              desugaredLibraryKeepRules,
              shrinkDesugaredLibrary)
          .withArtFrameworks()
          .withArt6Plus64BitsLib();

    } else {

      return testForD8()
          .addProgramFiles(filesToCompile)
          .addProgramFiles(getPathsFiles())
          .addProgramFiles(getSafeVarArgsFile())
          .addProgramFiles(testNGSupportProgramFiles())
          .addOptionsModification(opt -> opt.testing.trackDesugaredAPIConversions = true)
          .addLibraryFiles(getLibraryFile())
          .addLibraryFiles(JDK_11_JAVA_BASE_EXTENSION_CLASSES_DIR)
          .setMinApi(parameters.getApiLevel())
          .enableCoreLibraryDesugaring(parameters.getApiLevel(), keepRuleConsumer)
          .compile()
          .addDesugaredCoreLibraryRunClassPath(
              this::buildDesugaredLibraryWithJavaBaseExtension,
              parameters.getApiLevel(),
              keepRuleConsumer.get(),
              shrinkDesugaredLibrary)
          .withArtFrameworks()
          .withArt6Plus64BitsLib();
    }
  }

  private void runSuccessfulTests(D8TestCompileResult compileResult) throws Exception {
    String verbosity = "2"; // Increase verbosity for debugging.
    Map<String, String> runnableTests = getSuccessfulTests();
    for (String path : runnableTests.keySet()) {
      assert runnableTests.get(path) != null;
      D8TestRunResult result =
          compileResult.run(
              parameters.getRuntime(), "TestNGMainRunner", verbosity, runnableTests.get(path));
      assertTrue(
          "Failure in " + path + "\n" + result,
          result
              .getStdOut()
              .endsWith(
                  StringUtils.lines("Tests result in " + runnableTests.get(path) + ": SUCCESS")));
    }
  }

  private void runFailingTests(D8TestCompileResult compileResult) throws Exception {
    // For failing runnable tests, we just ensure that they do not fail due to desugaring, but
    // due to an expected failure (missing API, etc.).
    String verbosity = "2"; // Increase verbosity for debugging.
    Map<String, String> runnableTests = getFailingTests();
    for (String path : runnableTests.keySet()) {
      assert runnableTests.get(path) != null;
      D8TestRunResult result =
          compileResult.run(
              parameters.getRuntime(), "TestNGMainRunner", verbosity, runnableTests.get(path));
      String stdout = result.getStdOut();
      if (stdout.contains("java.lang.NoSuchMethodError")
          && Arrays.stream(missingDesugaredMethods()).anyMatch(stdout::contains)) {
        // TODO(b/134732760): support Java 9 APIs.
      } else if (stdout.contains("in class Ljava/util/Random")
          && stdout.contains("java.lang.NoSuchMethodError")) {
        // TODO(b/134732760): Random Java 9 Apis, support or do not use them.
      } else if (stdout.contains("java.lang.AssertionError")) {
        // TODO(b/134732760): Investigate and fix these issues.
      } else {
        String errorMessage = "STDOUT:\n" + result.getStdOut() + "STDERR:\n" + result.getStdErr();
        fail(errorMessage);
      }
    }
  }
}
