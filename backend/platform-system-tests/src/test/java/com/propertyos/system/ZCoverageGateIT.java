package com.propertyos.system;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.report.DirectorySourceFileLocator;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.MultiSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coverage gate. It runs last (the class name sorts after the other system tests), gathers what the unit tests,
 * the per-service integration tests and these system tests exercised, writes an HTML report and fails the build when
 * line coverage over all production code is below {@code coverage.minimum} percent (default 90).
 * <p>
 * Report: {@code backend/platform-system-tests/target/system/coverage/index.html}.
 */
class ZCoverageGateIT {
    /** Modules whose production code counts towards coverage. */
    private static final List<String> MODULES = List.of("platform-common", "api-gateway", "identity-service", "leasing-service",
            "billing-service", "maintenance-service", "workflow-service", "ai-service", "document-service");

    @Test
    void lineCoverageMeetsTheMinimum() throws Exception {
        SystemEnvironment env = SystemEnvironment.get();
        ExecFileLoader data = env.dumpCoverage();
        Path modules = env.modulesDir();
        for (String m : MODULES) {
            File exec = modules.resolve(m).resolve("target").resolve("jacoco.exec").toFile();
            if (exec.isFile()) data.load(exec);
        }
        data.save(env.workDir().resolve("jacoco-merged.exec").toFile(), false);

        double minimum = Double.parseDouble(System.getProperty("coverage.minimum", "90"));
        long coveredTotal = 0, missedTotal = 0, branchCovered = 0, branchMissed = 0;
        IBundleCoverage whole = null;
        CoverageBuilder all = new CoverageBuilder();
        MultiSourceFileLocator sources = new MultiSourceFileLocator(4);
        StringBuilder table = new StringBuilder("\nCoverage by module (line / branch):\n");
        for (String m : MODULES) {
            File classes = modules.resolve(m).resolve("target").resolve("classes").toFile();
            if (!classes.isDirectory()) continue;
            CoverageBuilder cb = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(data.getExecutionDataStore(), cb);
            analyzer.analyzeAll(classes);
            new Analyzer(data.getExecutionDataStore(), all).analyzeAll(classes);
            IBundleCoverage bundle = cb.getBundle(m);
            ICounter line = bundle.getLineCounter(), branch = bundle.getBranchCounter();
            coveredTotal += line.getCoveredCount();
            missedTotal += line.getMissedCount();
            branchCovered += branch.getCoveredCount();
            branchMissed += branch.getMissedCount();
            table.append(String.format("  %-20s %5.1f%% (%d/%d)   %5.1f%%%n", m, 100 * line.getCoveredRatio(),
                    line.getCoveredCount(), line.getTotalCount(), 100 * branch.getCoveredRatio()));
            File src = modules.resolve(m).resolve("src").resolve("main").resolve("java").toFile();
            if (src.isDirectory()) sources.add(new DirectorySourceFileLocator(src, "UTF-8", 4));
        }
        whole = all.getBundle("PropertyOS");
        Path report = env.workDir().resolve("coverage");
        Files.createDirectories(report);
        IReportVisitor html = new HTMLFormatter().createVisitor(new FileMultiReportOutput(report.toFile()));
        html.visitInfo(data.getSessionInfoStore().getInfos(), data.getExecutionDataStore().getContents());
        html.visitBundle(whole, sources);
        html.visitEnd();

        double percent = 100.0 * coveredTotal / (coveredTotal + missedTotal);
        table.append(String.format("  %-20s %5.1f%% (%d/%d)   %5.1f%%%n", "TOTAL", percent, coveredTotal, coveredTotal + missedTotal,
                100.0 * branchCovered / (branchCovered + branchMissed)));
        System.out.println(table);
        Files.writeString(env.workDir().resolve("coverage-summary.txt"), table.toString());
        assertTrue(percent >= minimum, String.format("Line coverage is %.1f%%, below the required %.0f%%. See %s", percent, minimum, report.resolve("index.html")));
    }
}
