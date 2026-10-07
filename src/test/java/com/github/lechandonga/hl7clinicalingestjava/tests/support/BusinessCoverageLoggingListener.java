package com.github.lechandonga.hl7clinicalingestjava.tests.support;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让测试日志直接呈现“业务 -> 用例 -> 结果”的覆盖矩阵，
 * 约定 @DisplayName 形如“业务: xxx / 用例: ... -> 结果: ...”。
 */
public class BusinessCoverageLoggingListener implements TestExecutionListener {

    private final Map<String, String> businessByUniqueId = new LinkedHashMap<>();

    @Override
    public void executionStarted(TestIdentifier identifier) {
        String business = findBusiness(identifier);
        if (business != null) {
            businessByUniqueId.put(identifier.getUniqueId(), business);
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!identifier.isTest()) {
            return;
        }
        String business = businessByUniqueId.getOrDefault(identifier.getUniqueId(), "未分组");
        String display = identifier.getDisplayName();
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> "PASS";
            case FAILED -> "FAIL";
            case ABORTED -> "ABORT";
        };
        System.out.println("[业务覆盖] " + business + " | " + display + " => " + status);
        if (result.getThrowable().isPresent() && result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            System.out.println("           原因: " + result.getThrowable().get());
        }
    }

    private String findBusiness(TestIdentifier identifier) {
        String display = identifier.getDisplayName();
        if (display != null && display.startsWith("业务:")) {
            return display.substring(3).trim();
        }
        return identifier.getParentId()
                .map(businessByUniqueId::get)
                .orElse(null);
    }
}
