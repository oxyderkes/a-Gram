package org.telegram.messenger;

import com.sun.source.util.JavacTask;
import java.util.Arrays;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse Android sources without pretending to type-check them against an absent SDK. */
public final class AgramSessionSourceSyntaxTest {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    Arrays.asList("-proc:none", "-encoding", "UTF-8"), null,
                    files.getJavaFileObjects(args));
            task.parse();
            for (Diagnostic<? extends JavaFileObject> item : diagnostics.getDiagnostics()) {
                if (item.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(item.toString());
            }
        }
        System.out.println("AgramSessionSourceSyntaxTest: parsed " + args.length + " sources");
    }
}
