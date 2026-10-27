package com.orbit.solver.core.engine;

import com.google.ortools.Loader;

/**
 * OR-Tools 是 JNI 包装，使用前必须先加载原生库。重复调用无副作用。
 *
 * <p>注意 JDK 版本：实测 <b>JDK 23 上求解会在 {@code msvcp140.dll} 里崩溃</b>
 * （{@code EXCEPTION_ACCESS_VIOLATION}，建模正常但 {@code CpSolver.solve()} 段错误），
 * 而 <b>JDK 21 LTS 正常</b>。项目统一使用 JDK 21。
 */
public final class SolverNatives {

    private static boolean loaded;

    public static synchronized void load() {
        if (!loaded) {
            Loader.loadNativeLibraries();
            loaded = true;
        }
    }

    private SolverNatives() {
    }
}
