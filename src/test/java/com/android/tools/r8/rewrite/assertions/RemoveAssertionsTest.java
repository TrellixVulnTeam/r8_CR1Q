// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.rewrite.assertions;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8TestCompileResult;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestCompileResult;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Function;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.BeforeParam;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

// This ASM class visitor has been adapted from
// https://chromium.googlesource.com/chromium/src/+/164e81fcd0828b40f5496e9025349ea728cde7f5/build/android/bytecode/java/org/chromium/bytecode/AssertionEnablerClassAdapter.java
// See b/110887293.

/**
 * An ClassVisitor for replacing Java ASSERT statements with a function by modifying Java bytecode.
 *
 * We do this in two steps, first step is to enable assert.
 * Following bytecode is generated for each class with ASSERT statements:
 * 0: ldc #8 // class CLASSNAME
 * 2: invokevirtual #9 // Method java/lang/Class.desiredAssertionStatus:()Z
 * 5: ifne 12
 * 8: iconst_1
 * 9: goto 13
 * 12: iconst_0
 * 13: putstatic #2 // Field $assertionsDisabled:Z
 * Replaces line #13 to the following:
 * 13: pop
 * Consequently, $assertionsDisabled is assigned the default value FALSE.
 * This is done in the first if statement in overridden visitFieldInsn. We do this per per-assert.
 *
 * Second step is to replace assert statement with a function:
 * The followed instructions are generated by a java assert statement:
 * getstatic     #3     // Field $assertionsDisabled:Z
 * ifne          118    // Jump to instruction as if assertion if not enabled
 * ...
 * ifne          19
 * new           #4     // class java/lang/AssertionError
 * dup
 * ldc           #5     // String (don't have this line if no assert message given)
 * invokespecial #6     // Method java/lang/AssertionError.
 * athrow
 * Replace athrow with:
 * invokestatic  #7     // Method org/chromium/base/JavaExceptionReporter.assertFailureHandler
 * goto          118
 * JavaExceptionReporter.assertFailureHandler is a function that handles the AssertionError,
 * 118 is the instruction to execute as if assertion if not enabled.
 */
class AssertionEnablerClassAdapter extends ClassVisitor {
  AssertionEnablerClassAdapter(ClassVisitor visitor) {
    super(InternalOptions.ASM_VERSION, visitor);
  }

  @Override
  public MethodVisitor visitMethod(final int access, final String name, String desc,
      String signature, String[] exceptions) {
    return new RewriteAssertMethodVisitor(
        Opcodes.ASM5, super.visitMethod(access, name, desc, signature, exceptions));
  }

  static class RewriteAssertMethodVisitor extends MethodVisitor {
    static final String ASSERTION_DISABLED_NAME = "$assertionsDisabled";
    static final String INSERT_INSTRUCTION_NAME = "assertFailureHandler";
    static final String INSERT_INSTRUCTION_DESC =
        Type.getMethodDescriptor(Type.VOID_TYPE, Type.getObjectType("java/lang/AssertionError"));
    static final boolean INSERT_INSTRUCTION_ITF = false;

    boolean mStartLoadingAssert;
    Label mGotoLabel;

    public RewriteAssertMethodVisitor(int api, MethodVisitor mv) {
      super(api, mv);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      if (opcode == Opcodes.PUTSTATIC && name.equals(ASSERTION_DISABLED_NAME)) {
        super.visitInsn(Opcodes.POP); // enable assert
      } else if (opcode == Opcodes.GETSTATIC && name.equals(ASSERTION_DISABLED_NAME)) {
        mStartLoadingAssert = true;
        super.visitFieldInsn(opcode, owner, name, desc);
      } else {
        super.visitFieldInsn(opcode, owner, name, desc);
      }
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
      if (mStartLoadingAssert && opcode == Opcodes.IFNE && mGotoLabel == null) {
        mGotoLabel = label;
      }
      super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitInsn(int opcode) {
      if (!mStartLoadingAssert || opcode != Opcodes.ATHROW) {
        super.visitInsn(opcode);
      } else {
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ChromuimAssertionHookMock.class.getCanonicalName().replace('.', '/'),
            INSERT_INSTRUCTION_NAME,
            INSERT_INSTRUCTION_DESC,
            INSERT_INSTRUCTION_ITF);
        super.visitJumpInsn(Opcodes.GOTO, mGotoLabel);
        mStartLoadingAssert = false;
        mGotoLabel = null;
      }
    }
  }
}

class CompilationResults {

  final R8TestCompileResult allowAccess;
  final R8TestCompileResult withAssertions;
  final R8TestCompileResult withoutAssertions;

  CompilationResults(
      R8TestCompileResult allowAccess,
      R8TestCompileResult withAssertions,
      R8TestCompileResult withoutAssertions) {
    this.allowAccess = allowAccess;
    this.withAssertions = withAssertions;
    this.withoutAssertions = withoutAssertions;
  }
}

@RunWith(Parameterized.class)
public class RemoveAssertionsTest extends TestBase {

  private final TestParameters parameters;

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    return buildParameters(getTestParameters().withAllRuntimes().build());
  }

  public RemoveAssertionsTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @ClassRule public static TemporaryFolder staticTemp = ToolHelper.getTemporaryFolderForTest();

  @BeforeParam
  public static void forceCompilation(TestParameters parameters) {
    compilationResults.apply(parameters.getBackend());
  }

  private static Function<Backend, CompilationResults> compilationResults =
      memoizeFunction(RemoveAssertionsTest::compileAll);

  private static R8TestCompileResult compileWithAccessModification(Backend backend)
      throws CompilationFailedException {
    return testForR8(staticTemp, backend)
        .addProgramClassFileData(ClassWithAssertionsDump.dump())
        .addKeepMainRule(ClassWithAssertions.class)
        .addKeepRules("-allowaccessmodification", "-dontobfuscate")
        .addOptionsModification(o -> o.enableInlining = false)
        .compile();
  }

  private static R8TestCompileResult compileCf(boolean disableAssertions)
      throws CompilationFailedException {
    return testForR8(staticTemp, Backend.CF)
        .addProgramClassFileData(ClassWithAssertionsDump.dump())
        .debug()
        .noTreeShaking()
        .noMinification()
        .addOptionsModification(o -> o.disableAssertions = disableAssertions)
        .compile();
  }

  private static byte[] identity(byte[] classBytes) {
    return classBytes;
  }

  private static byte[] chromiumAssertionEnabler(byte[] classBytes) {
    ClassWriter writer = new ClassWriter(0);
    new ClassReader(classBytes).accept(new AssertionEnablerClassAdapter(writer), 0);
    return writer.toByteArray();
  }

  private static R8TestCompileResult compileRegress110887293(Function<byte[], byte[]> rewriter)
      throws CompilationFailedException {
    return testForR8(staticTemp, Backend.DEX)
        .addProgramClassFileData(
            rewriter.apply(ClassWithAssertionsDump.dump()), ChromuimAssertionHookMockDump.dump())
        .setMinApi(AndroidApiLevel.B)
        .debug()
        .noTreeShaking()
        .noMinification()
        .compile();
  }

  private static CompilationResults compileAll(Backend backend) throws CompilationFailedException {
    R8TestCompileResult withAccess = compileWithAccessModification(backend);
    if (backend == Backend.CF) {
      return new CompilationResults(withAccess, compileCf(false), compileCf(true));
    }
    return new CompilationResults(
        withAccess,
        compileRegress110887293(RemoveAssertionsTest::chromiumAssertionEnabler),
        compileRegress110887293(RemoveAssertionsTest::identity));
  }

  private void checkResultWithAssertionsPresent(TestCompileResult result) throws Exception {
    if (parameters.isDexRuntime()) {
      // Java assertions have no effect on Android (-ea is not implemented).
      checkResultWithAssertionsRemoved(result);
    } else {
      String main = ClassWithAssertions.class.getCanonicalName();
      result
          .enableRuntimeAssertions()
          .run(parameters.getRuntime(), main, "0")
          .assertFailureWithOutput(StringUtils.lines("1"));
      // Assertion is not hit.
      result
          .enableRuntimeAssertions()
          .run(parameters.getRuntime(), main, "1")
          .assertSuccessWithOutput(StringUtils.lines("1", "2"));
    }
  }

  private void checkResultWithAssertionsRemoved(TestCompileResult result) throws Exception {
    String main = ClassWithAssertions.class.getCanonicalName();
    result
        .run(parameters.getRuntime(), main, "0")
        .assertSuccessWithOutput(StringUtils.lines("1", "2"));
    result
        .run(parameters.getRuntime(), main, "1")
        .assertSuccessWithOutput(StringUtils.lines("1", "2"));
  }

  private void checkResultWithChromiumAssertions(TestCompileResult result) throws Exception {
    String main = ClassWithAssertions.class.getCanonicalName();
    result
        .run(parameters.getRuntime(), main, "0")
        .assertSuccessWithOutput(
            StringUtils.lines("1", "Got AssertionError java.lang.AssertionError", "2"));
    result
        .run(parameters.getRuntime(), main, "1")
        .assertSuccessWithOutput(StringUtils.lines("1", "2"));
  }

  @Test
  public void test() throws Exception {
    // TODO(mkroghj) Why does this fail on JDK?
    assumeTrue(parameters.isDexRuntime());
    // Run with R8, but avoid inlining to really validate that the methods "condition"
    // and "<clinit>" are gone.
    CompilationResults results = compilationResults.apply(parameters.getBackend());
    CodeInspector inspector = results.allowAccess.inspector();
    ClassSubject clazz = inspector.clazz(ClassWithAssertions.class);
    assertTrue(clazz.isPresent());
    MethodSubject conditionMethod =
        clazz.method(new MethodSignature("condition", "boolean", new String[]{}));
    assertTrue(!conditionMethod.isPresent());
    MethodSubject clinit =
        clazz.method(new MethodSignature(Constants.CLASS_INITIALIZER_NAME, "void", new String[]{}));
    assertTrue(!clinit.isPresent());
  }

  @Test
  public void testCfOutput() throws Exception {
    assumeTrue(parameters.isCfRuntime());
    CompilationResults results = compilationResults.apply(parameters.getBackend());
    // Assertion is hit.
    checkResultWithAssertionsPresent(results.withAssertions);
    // Assertion is hit, but removed.
    checkResultWithAssertionsRemoved(results.withoutAssertions);
  }

  @Test
  public void regress110887293() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    CompilationResults results = compilationResults.apply(parameters.getBackend());
    // Assertions removed for default assertion code.
    checkResultWithAssertionsRemoved(results.withoutAssertions);
    // Assertions not removed when default assertion code is not present.
    checkResultWithChromiumAssertions(results.withAssertions);
  }

  private D8TestCompileResult compileD8(boolean disableAssertions)
      throws CompilationFailedException {
    return testForD8()
        .addProgramClassFileData(ClassWithAssertionsDump.dump())
        .debug()
        .setMinApi(AndroidApiLevel.B)
        .addOptionsModification(o -> o.disableAssertions = disableAssertions)
        .compile();
  }

  private D8TestCompileResult compileR8FollowedByD8(boolean disableAssertions) throws Exception {
    Path x =
        testForR8(Backend.CF)
            .addProgramClassFileData(ClassWithAssertionsDump.dump())
            .debug()
            .setMinApi(AndroidApiLevel.B)
            .noTreeShaking()
            .noMinification()
            .compile()
            .writeToZip();

    return testForD8()
        .addProgramFiles(x)
        .debug()
        .setMinApi(AndroidApiLevel.B)
        .addOptionsModification(o -> o.disableAssertions = disableAssertions)
        .compile();
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    checkResultWithAssertionsRemoved(compileD8(true));
    checkResultWithAssertionsPresent(compileD8(false));
  }

  private D8TestCompileResult compileD8Regress110887293(Function<byte[], byte[]> rewriter)
      throws CompilationFailedException {
    return testForD8()
        .addProgramClassFileData(
            rewriter.apply(ClassWithAssertionsDump.dump()),
            rewriter.apply(ChromuimAssertionHookMockDump.dump()))
        .debug()
        .setMinApi(AndroidApiLevel.B)
        .compile();
  }

  @Test
  public void testD8Regress110887293() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    checkResultWithChromiumAssertions(
        compileD8Regress110887293(RemoveAssertionsTest::chromiumAssertionEnabler));
  }

  @Test
  public void testR8FollowedByD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    checkResultWithAssertionsRemoved(compileR8FollowedByD8(true));
    checkResultWithAssertionsPresent(compileR8FollowedByD8(false));
  }
}
