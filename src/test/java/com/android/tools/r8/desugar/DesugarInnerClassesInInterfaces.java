// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.desugar;

import static com.android.tools.r8.ir.desugar.itf.InterfaceDesugaringForTesting.getCompanionClassNameSuffix;

import com.android.tools.r8.DesugarTestConfiguration;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class DesugarInnerClassesInInterfaces extends TestBase {

  private final List<String> EXPECTED_RESULT_WITHOUT_DESUGARING =
      ImmutableList.of(
          WithAnonymousInner.class.getName(), "true", WithLocalInner.class.getName(), "true");

  private final List<String> EXPECTED_RESULT_WITH_DESUGARING =
      ImmutableList.of(
          WithAnonymousInner.class.getName() + getCompanionClassNameSuffix(),
          "true",
          WithLocalInner.class.getName() + getCompanionClassNameSuffix(),
          "true");

  private final List<String> EXPECTED_RESULT_WITH_DESUGARING_B168697955 =
      ImmutableList.of(
          WithAnonymousInner.class.getName() + getCompanionClassNameSuffix(),
          "false",
          WithLocalInner.class.getName() + getCompanionClassNameSuffix(),
          "false");

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().withAllApiLevelsAlsoForCf().build();
  }

  private final TestParameters parameters;

  public DesugarInnerClassesInInterfaces(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testDesugar() throws Exception {
    testForDesugaring(parameters)
        .addInnerClasses(DesugarInnerClassesInInterfaces.class)
        .run(parameters.getRuntime(), TestClass.class)
        .applyIf(
            DesugarTestConfiguration::isNotDesugared,
            r -> r.assertSuccessWithOutputLines(EXPECTED_RESULT_WITHOUT_DESUGARING))
        .applyIf(
            DesugarTestConfiguration::isDesugared,
            r ->
                r.assertSuccessWithOutputLines(
                    parameters
                            .getApiLevel()
                            .isGreaterThanOrEqualTo(apiLevelWithDefaultInterfaceMethodsSupport())
                        ? EXPECTED_RESULT_WITHOUT_DESUGARING
                        : EXPECTED_RESULT_WITH_DESUGARING));
  }

  @Test
  public void testR8Compat() throws Exception {
    testForR8Compat(parameters.getBackend())
        .addInnerClasses(DesugarInnerClassesInInterfaces.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepAllClassesRule()
        .addKeepAttributeInnerClassesAndEnclosingMethod()
        .noHorizontalClassMergingOfSynthetics()
        .compile()
        .run(parameters.getRuntime(), TestClass.class)
        .applyIf(
            parameters.canUseDefaultAndStaticInterfaceMethods(),
            result -> result.assertSuccessWithOutputLines(EXPECTED_RESULT_WITHOUT_DESUGARING),
            // The static method which is moved to the companion class is inlined and causing
            // this different output. The rule "-keep class * { *; }" does not keep the static
            // method from being inlined after it has moved. Turning off inlining produces the
            // expected result. The inlining cause the getEnclosingClass() to return null.
            // See b/168697955.
            result ->
                result.assertSuccessWithOutputLines(EXPECTED_RESULT_WITH_DESUGARING_B168697955));
  }

  @Test
  public void testR8_B168697955() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(DesugarInnerClassesInInterfaces.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepAllClassesRule()
        .addKeepAttributeInnerClassesAndEnclosingMethod()
        // With inlining turned off we get the expected result.
        .addOptionsModification(options -> options.enableInlining = false)
        .compile()
        .run(parameters.getRuntime(), TestClass.class)
        .applyIf(
            parameters.canUseDefaultAndStaticInterfaceMethods(),
            result -> result.assertSuccessWithOutputLines(EXPECTED_RESULT_WITHOUT_DESUGARING),
            // TODO(b/187377562): We remove the attribute due to not pinning the moved methods.
            result -> result.assertFailureWithErrorThatThrows(NullPointerException.class));
  }

  @Test
  public void testR8Full() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(DesugarInnerClassesInInterfaces.class)
        .setMinApi(parameters.getApiLevel())
        .addKeepAllClassesRule()
        .addKeepAttributeInnerClassesAndEnclosingMethod()
        .compile()
        .run(parameters.getRuntime(), TestClass.class)
        .applyIf(
            parameters.canUseDefaultAndStaticInterfaceMethods(),
            result -> result.assertSuccessWithOutputLines(EXPECTED_RESULT_WITHOUT_DESUGARING),
            // TODO(b/187377562): We remove the attribute due to not pinning the moved methods.
            result -> result.assertFailureWithErrorThatThrows(NullPointerException.class));
  }

  interface WithAnonymousInner {
    static Callable<Class<?>> staticOuter() {
      return new Callable<Class<?>>() {
        @Override
        public Class<?> call() {
          return getClass().getEnclosingClass();
        }
      };
    }

    default Callable<Class<?>> defaultOuter() {
      return new Callable<Class<?>>() {
        @Override
        public Class<?> call() {
          return getClass().getEnclosingClass();
        }
      };
    }
  }

  interface WithLocalInner {
    static Callable<Class<?>> staticOuter() {
      class Local implements Callable<Class<?>> {
        @Override
        public Class<?> call() {
          return getClass().getEnclosingClass();
        }
      }
      return new Local();
    }

    default Callable<Class<?>> defaultOuter() {
      class Local implements Callable<Class<?>> {
        @Override
        public Class<?> call() {
          return getClass().getEnclosingClass();
        }
      }
      return new Local();
    }
  }

  public static class TestClass {

    public static void main(String[] args) throws Exception {
      System.out.println(new WithAnonymousInner() {}.defaultOuter().call().getName());
      System.out.println(
          new WithAnonymousInner() {}.defaultOuter()
              .call()
              .equals(WithAnonymousInner.staticOuter().call()));
      System.out.println(new WithLocalInner() {}.defaultOuter().call().getName());
      System.out.println(
          new WithLocalInner() {}.defaultOuter()
              .call()
              .equals(WithLocalInner.staticOuter().call()));
    }
  }
}
