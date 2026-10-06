package com.github.lechandonga.siemalertcorrelationjava.tests;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 让测试运行日志明确体现：覆盖了哪些业务、每个用例的名称与结果。
 */
public class BusinessLogExtension implements TestWatcher, AfterAllCallback {
    private static final Logger log = LoggerFactory.getLogger("BUSINESS-COVERAGE");
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    @Override
    public void testSuccessful(ExtensionContext context) {
        passed.incrementAndGet();
        log.info("[业务:{}] 用例[{}] 结果=PASS", business(context), context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        failed.incrementAndGet();
        log.error("[业务:{}] 用例[{}] 结果=FAIL 原因={}", business(context), context.getDisplayName(),
                cause.toString());
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        failed.incrementAndGet();
        log.warn("[业务:{}] 用例[{}] 结果=ABORTED", business(context), context.getDisplayName());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        String business = context.getTestClass().map(Class::getSimpleName).orElse("?");
        log.info("========== 业务[{}] 覆盖结束：PASS={} FAIL={} ==========",
                business, passed.get(), failed.get());
    }

    private String business(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?");
    }
}
