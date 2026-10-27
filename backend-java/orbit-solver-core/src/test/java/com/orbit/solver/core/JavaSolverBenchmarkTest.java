package com.orbit.solver.core;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.orbit.solver.core.compile.CompiledModel;
import com.orbit.solver.core.engine.SolveOutcome;
import com.orbit.solver.core.engine.SolverNatives;
import com.orbit.solver.core.engine.SolverSettings;
import com.orbit.solver.core.legacy.FaithfulModelBuilder;
import com.orbit.solver.core.model.ScheduleInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Run explicitly with -Dtest=JavaSolverBenchmarkTest -Dbench.real=... -Dbench.output=... */
public class JavaSolverBenchmarkTest {
    private final List<String> rows = new ArrayList<>();

    @Test
    void benchmark() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.enabled"),
                "Opt-in benchmark: add -Dbench.enabled=true");
        SolverNatives.load();
        if (!System.getProperty("bench.fairFixture", "").isBlank()) {
            if (Integer.getInteger("bench.fairConcurrency", 1) == 2) {
                fairConcurrentScenario();
            } else {
                fairScenario();
            }
            return;
        }
        int targetClasses = Integer.getInteger("bench.targetClasses", 0);
        if (targetClasses > 0) {
            capacityScenario(targetClasses);
            return;
        }
        rows.add("scenario,implementation,phase,iteration,classes,teachers,assignments,variables,constraints,elapsed_ms,status,objective,bound,entries,valid");
        ScheduleInput school8 = TestFixtures.school8();
        // Repeated schools have disjoint teacher IDs; this probes model-building scale.
        compileScenario("school8", school8, 5);
        compileScenario("synthetic16", replicate(school8, 2), 3);
        compileScenario("synthetic32", replicate(school8, 4), 3);
        solveScenario("school8", school8, 5.0, 3);

        String realPath = System.getProperty("bench.real", "");
        if (!realPath.isBlank()) {
            ScheduleInput real = FixtureLoader.load(Path.of(realPath));
            compileScenario("real21_topology_no_class_fixed", real, 3);
            solveScenario("real21_topology_no_class_fixed", real, 10.0, 3);
        }
        Path output = Path.of(System.getProperty("bench.output", "target/java-solver-benchmark.csv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, rows);
        System.out.println("BENCHMARK_CSV=" + output.toAbsolutePath());
        rows.forEach(System.out::println);
    }

    private record FairRun(double compileMs, double solveMs, String status,
                           int entries, boolean valid, int variables, int constraints) {
    }

    private static FairRun runFair(ScheduleInput input, int workers, double limitSeconds) {
        long started = System.nanoTime();
        CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
        double compileMs = (System.nanoTime() - started) / 1_000_000.0;
        started = System.nanoTime();
        SolveOutcome outcome = OrbitSolver.solve(compiled,
                new SolverSettings(limitSeconds, workers, false));
        double solveMs = (System.nanoTime() - started) / 1_000_000.0;
        boolean valid = outcome.hasSolution() && outcome.entries().size() == expectedEntries(input)
                && TestFixtures.checkInvariants(input, outcome.entries()).isEmpty();
        return new FairRun(compileMs, solveMs, outcome.status().toString(),
                outcome.entries().size(), valid,
                compiled.model().getBuilder().getVariablesCount(),
                compiled.model().getBuilder().getConstraintsCount());
    }

    private void fairConcurrentScenario() throws Exception {
        ScheduleInput input = FixtureLoader.load(Path.of(System.getProperty("bench.fairFixture")));
        int workers = Integer.getInteger("bench.workers", 2);
        double limitSeconds = Double.parseDouble(System.getProperty("bench.limitSeconds", "12"));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        FairRun first;
        FairRun second;
        long started = System.nanoTime();
        try {
            Future<FairRun> a = pool.submit(() -> { start.await(); return runFair(input, workers, limitSeconds); });
            Future<FairRun> b = pool.submit(() -> { start.await(); return runFair(input, workers, limitSeconds); });
            start.countDown();
            first = a.get();
            second = b.get();
        } finally {
            pool.shutdownNow();
        }
        double totalMs = (System.nanoTime() - started) / 1_000_000.0;
        Path output = Path.of(System.getProperty("bench.output", "target/java-fair-concurrent.csv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> lines = List.of(
                "task,classes,workers_each,limit_seconds,compile_ms,solve_ms,status,entries,valid,variables,constraints,total_batch_ms",
                fairRow(1, input.classes().size(), workers, limitSeconds, first, totalMs),
                fairRow(2, input.classes().size(), workers, limitSeconds, second, totalMs));
        Files.write(output, lines);
        System.out.println("FAIR_CONCURRENT_CSV=" + output.toAbsolutePath());
        lines.forEach(System.out::println);
    }

    private static String fairRow(int task, int classes, int workers, double limit,
                                  FairRun run, double totalMs) {
        return String.format(Locale.ROOT,
                "%d,%d,%d,%.1f,%.3f,%.3f,%s,%d,%s,%d,%d,%.3f",
                task, classes, workers, limit, run.compileMs(), run.solveMs(),
                run.status(), run.entries(), run.valid(), run.variables(),
                run.constraints(), totalMs);
    }

    private void fairScenario() throws Exception {
        ScheduleInput input = FixtureLoader.load(Path.of(System.getProperty("bench.fairFixture")));
        int workers = Integer.getInteger("bench.workers", 2);
        double limitSeconds = Double.parseDouble(System.getProperty("bench.limitSeconds", "12"));
        long started = System.nanoTime();
        CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
        double compileMs = (System.nanoTime() - started) / 1_000_000.0;
        started = System.nanoTime();
        SolveOutcome outcome = OrbitSolver.solve(compiled,
                new SolverSettings(limitSeconds, workers, false));
        double solveMs = (System.nanoTime() - started) / 1_000_000.0;
        int expected = expectedEntries(input);
        boolean valid = outcome.hasSolution() && outcome.entries().size() == expected
                && TestFixtures.checkInvariants(input, outcome.entries()).isEmpty();
        Path output = Path.of(System.getProperty("bench.output", "target/java-fair.csv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> lines = List.of(
                "classes,teachers,assignments,expected_entries,variables,constraints,workers,limit_seconds,compile_ms,solve_ms,total_ms,status,objective,bound,entries,valid,max_heap_mb",
                String.format(Locale.ROOT,
                        "%d,%d,%d,%d,%d,%d,%d,%.1f,%.3f,%.3f,%.3f,%s,%.3f,%.3f,%d,%s,%d",
                        input.classes().size(), input.teachers().size(), input.assignments().size(),
                        expected, compiled.model().getBuilder().getVariablesCount(),
                        compiled.model().getBuilder().getConstraintsCount(), workers, limitSeconds,
                        compileMs, solveMs, compileMs + solveMs, outcome.status(),
                        outcome.hasSolution() ? (double) outcome.objectiveValue() : Double.NaN,
                        (double) outcome.bestObjectiveBound(), outcome.entries().size(), valid,
                        Runtime.getRuntime().maxMemory() / 1_048_576));
        Files.write(output, lines);
        System.out.println("FAIR_CSV=" + output.toAbsolutePath());
        lines.forEach(System.out::println);
    }

    private static int expectedEntries(ScheduleInput input) {
        Map<Long, Integer> hoursByGrade = new HashMap<>();
        for (var gs : input.gradeSubjects()) {
            hoursByGrade.merge(gs.gradeId(), gs.weeklyHours(), Integer::sum);
        }
        return input.classes().stream().mapToInt(c -> hoursByGrade.getOrDefault(c.gradeId(), 0)).sum();
    }

    private void capacityScenario(int targetClasses) throws Exception {
        ScheduleInput source = FixtureLoader.load(Path.of(System.getProperty("bench.real")));
        int copies = (targetClasses + source.classes().size() - 1) / source.classes().size();
        if (copies > 32) throw new IllegalArgumentException("At most 32 copies per run");
        ScheduleInput input = firstClasses(replicate(source, copies), targetClasses);
        double limitSeconds = Double.parseDouble(System.getProperty("bench.limitSeconds", "10"));
        long start = System.nanoTime();
        CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
        double compileMs = (System.nanoTime() - start) / 1_000_000.0;
        long heapAfterCompile = usedHeapMb();
        start = System.nanoTime();
        SolveOutcome outcome = OrbitSolver.solve(compiled,
                new SolverSettings(limitSeconds, 1, false));
        double solveMs = (System.nanoTime() - start) / 1_000_000.0;
        long heapAfterSolve = usedHeapMb();
        int expectedEntries = expectedEntries(input);
        boolean valid = outcome.hasSolution() && outcome.entries().size() == expectedEntries
                && TestFixtures.checkInvariants(input, outcome.entries()).isEmpty();
        Path output = Path.of(System.getProperty("bench.output", "target/java-solver-capacity.csv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        List<String> lines = List.of(
                "copies,classes,teachers,assignments,expected_entries,variables,constraints,compile_ms,solve_ms,limit_seconds,status,objective,bound,entries,valid,heap_after_compile_mb,heap_after_solve_mb,max_heap_mb",
                String.format(Locale.ROOT,
                        "%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.1f,%s,%.3f,%.3f,%d,%s,%d,%d,%d",
                        copies, input.classes().size(), input.teachers().size(),
                        input.assignments().size(), expectedEntries,
                        compiled.model().getBuilder().getVariablesCount(),
                        compiled.model().getBuilder().getConstraintsCount(), compileMs,
                        solveMs, limitSeconds, outcome.status(),
                        outcome.hasSolution() ? (double) outcome.objectiveValue() : Double.NaN,
                        (double) outcome.bestObjectiveBound(), outcome.entries().size(), valid,
                        heapAfterCompile, heapAfterSolve, Runtime.getRuntime().maxMemory() / 1_048_576));
        Files.write(output, lines);
        System.out.println("CAPACITY_CSV=" + output.toAbsolutePath());
        lines.forEach(System.out::println);
    }

    private static long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576;
    }

    private static ScheduleInput firstClasses(ScheduleInput input, int count) {
        List<ScheduleInput.ClassInfo> classes = input.classes().subList(0, count);
        Set<Long> classIds = new HashSet<>();
        Set<Long> gradeIds = new HashSet<>();
        for (var c : classes) {
            classIds.add(c.id());
            gradeIds.add(c.gradeId());
        }
        List<ScheduleInput.Assignment> assignments = input.assignments().stream()
                .filter(a -> classIds.contains(a.classId())).toList();
        Set<Long> teacherIds = new HashSet<>();
        Set<Long> subjectIds = new HashSet<>();
        for (var a : assignments) {
            teacherIds.add(a.teacherId());
            subjectIds.add(a.subjectId());
        }
        return new ScheduleInput(input.organizationId(),
                input.grades().stream().filter(g -> gradeIds.contains(g.id())).toList(),
                classes,
                input.subjects().stream().filter(s -> subjectIds.contains(s.id())).toList(),
                input.teachers().stream().filter(t -> teacherIds.contains(t.id())).toList(),
                assignments,
                input.gradeSubjects().stream().filter(gs -> gradeIds.contains(gs.gradeId())).toList(),
                input.gradeFixedSlots().stream().filter(f -> gradeIds.contains(f.gradeId())).toList());
    }

    private void compileScenario(String name, ScheduleInput input, int repetitions) {
        // One warm-up pair, then alternate order to reduce JIT/order bias.
        compileOnce(name, input, "legacy", 0);
        compileOnce(name, input, "rule_compiler", 0);
        for (int i = 1; i <= repetitions; i++) {
            if (i % 2 == 0) {
                compileOnce(name, input, "rule_compiler", i);
                compileOnce(name, input, "legacy", i);
            } else {
                compileOnce(name, input, "legacy", i);
                compileOnce(name, input, "rule_compiler", i);
            }
        }
    }

    private void compileOnce(String name, ScheduleInput input, String implementation, int iteration) {
        long start = System.nanoTime();
        CpModel model;
        if (implementation.equals("legacy")) {
            model = new FaithfulModelBuilder(input).build();
        } else {
            CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
            model = compiled.model();
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        if (iteration > 0) {
            row(name, implementation, "compile", iteration, input, model, elapsed,
                    "", 0, 0, 0, "");
        }
    }

    private void solveScenario(String name, ScheduleInput input, double limitSeconds, int repetitions) {
        // Both implementations get the same input, native engine, one worker and time limit.
        // Alternate execution order to reduce any warm-cache advantage.
        for (int iteration = 1; iteration <= repetitions; iteration++) {
            if (iteration % 2 == 0) {
                solveRule(name, input, limitSeconds, iteration);
                solveLegacy(name, input, limitSeconds, iteration);
            } else {
                solveLegacy(name, input, limitSeconds, iteration);
                solveRule(name, input, limitSeconds, iteration);
            }
        }
    }

    private void solveLegacy(String name, ScheduleInput input, double limitSeconds, int iteration) {
        CpModel legacy = new FaithfulModelBuilder(input).build();
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(limitSeconds);
        solver.getParameters().setNumSearchWorkers(1);
        long start = System.nanoTime();
        CpSolverStatus status = solver.solve(legacy);
        boolean hasSolution = status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE;
        row(name, "legacy", "solve", iteration, input, legacy,
                (System.nanoTime() - start) / 1_000_000.0, status.toString(),
                hasSolution ? solver.objectiveValue() : Double.NaN,
                solver.bestObjectiveBound(), 0, "not_extracted");
    }

    private void solveRule(String name, ScheduleInput input, double limitSeconds, int iteration) {
        CompiledModel compiled = OrbitSolver.compile(input, TestFixtures.legacyEquivalentRules());
        long start = System.nanoTime();
        SolveOutcome outcome = OrbitSolver.solve(compiled,
                new SolverSettings(limitSeconds, 1, false));
        boolean valid = outcome.hasSolution()
                && TestFixtures.checkInvariants(input, outcome.entries()).isEmpty();
        row(name, "rule_compiler", "solve", iteration, input, compiled.model(),
                (System.nanoTime() - start) / 1_000_000.0, outcome.status().toString(),
                outcome.hasSolution() ? outcome.objectiveValue() : Double.NaN,
                outcome.bestObjectiveBound(),
                outcome.entries().size(), Boolean.toString(valid));
    }

    private void row(String name, String implementation, String phase, int iteration,
                     ScheduleInput input, CpModel model, double elapsed,
                     String status, double objective, double bound, int entries, String valid) {
        rows.add(String.format(Locale.ROOT,
                "%s,%s,%s,%d,%d,%d,%d,%d,%d,%.3f,%s,%.3f,%.3f,%d,%s",
                name, implementation, phase, iteration, input.classes().size(),
                input.teachers().size(), input.assignments().size(),
                model.getBuilder().getVariablesCount(), model.getBuilder().getConstraintsCount(),
                elapsed, status, objective, bound, entries, valid));
    }

    private static ScheduleInput replicate(ScheduleInput source, int copies) {
        List<ScheduleInput.GradeInfo> grades = new ArrayList<>();
        List<ScheduleInput.ClassInfo> classes = new ArrayList<>();
        List<ScheduleInput.SubjectInfo> subjects = new ArrayList<>();
        List<ScheduleInput.TeacherInfo> teachers = new ArrayList<>();
        List<ScheduleInput.Assignment> assignments = new ArrayList<>();
        List<ScheduleInput.GradeSubjectInfo> gradeSubjects = new ArrayList<>();
        List<ScheduleInput.FixedSlotRule> fixed = new ArrayList<>();
        for (int copy = 0; copy < copies; copy++) {
            long offset = copy * 1_000_000L;
            for (var item : source.grades()) grades.add(new ScheduleInput.GradeInfo(
                    item.id() + offset, item.name() + "_" + copy, item.periodsPerDay()));
            for (var item : source.classes()) classes.add(new ScheduleInput.ClassInfo(
                    item.id() + offset, item.name() + "_" + copy, item.gradeId() + offset));
            for (var item : source.subjects()) subjects.add(new ScheduleInput.SubjectInfo(
                    item.id() + offset, item.name() + "_" + copy, item.category()));
            for (var item : source.teachers()) teachers.add(new ScheduleInput.TeacherInfo(
                    item.id() + offset, item.name() + "_" + copy,
                    item.subjectId() + offset, item.maxWeeklyHours()));
            for (var item : source.assignments()) assignments.add(new ScheduleInput.Assignment(
                    item.classId() + offset, item.subjectId() + offset, item.teacherId() + offset));
            for (var item : source.gradeSubjects()) gradeSubjects.add(new ScheduleInput.GradeSubjectInfo(
                    item.gradeId() + offset, item.subjectId() + offset, item.weeklyHours()));
            for (var item : source.gradeFixedSlots()) fixed.add(new ScheduleInput.FixedSlotRule(
                    item.gradeId() + offset, item.subjectId() + offset, item.day(), item.period()));
        }
        return new ScheduleInput(source.organizationId(), grades, classes, subjects,
                teachers, assignments, gradeSubjects, fixed);
    }
}
