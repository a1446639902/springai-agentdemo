package io.github.javaside.springai.codetui.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 超时装饰器：给没有自带超时的工具兜一层总超时。 */
class TimeLimitedToolCallbackTest {

    /** 可配置耗时与抛错行为的假工具。 */
    private static final class FakeTool implements ToolCallback {
        private final long sleepMillis;
        private final RuntimeException toThrow;

        FakeTool(long sleepMillis, RuntimeException toThrow) {
            this.sleepMillis = sleepMillis;
            this.toThrow = toThrow;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("SlowTool").description("d").inputSchema("{}").build();
        }

        @Override public String call(String toolInput) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return "fake-result";
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }

    @Test
    void fastCallReturnsNormally() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("fake-result", limited.call("{}"), "未超时应原样返回委托结果");
    }

    @Test
    void slowCallThrowsReadableTimeout() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(2000, null), Duration.ofMillis(100));

        ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> limited.call("{}"));

        assertTrue(ex.getMessage().contains("SlowTool"), "错误消息应点名是哪个工具超时，实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("超时"), "应是可读的超时提示，实际=" + ex.getMessage());
    }

    /**
     * 生产事故回归（2026-09-06 回合 10）：超时抛 IllegalStateException 时穿透
     * DefaultToolCallingManager（只 catch ToolExecutionException）与 ResilientToolCallingManager
     * （只接工具名解析失败），一路杀到 CodingAgent 把整回合标失败。超时必须包成
     * ToolExecutionException 才能落进 ResilientToolExecutionExceptionProcessor 转文本回模型。
     */
    @Test
    void timeoutIsWrappedAsToolExecutionException() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(2000, null), Duration.ofMillis(100));

        ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> limited.call("{}"));

        assertEquals("SlowTool", ex.getToolDefinition().name(),
                "须携带工具定义，ResilientToolExecutionExceptionProcessor 靠它点名工具");
        assertNotNull(ex.getCause(), "cause 保留 TimeoutException 供定位");
        assertTrue(ex.getMessage().contains("超时"),
                "message 应保留可读超时文案（processor 取首个非空 message 回模型），实际=" + ex.getMessage());
    }

    /** 中断分支同病同修：Esc 取消回合时工具被 cancel(true)，同样不能让 IllegalStateException 杀回合。 */
    @Test
    void interruptIsWrappedAsToolExecutionException() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        ToolCallback blocking = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("BlockingTool").description("d").inputSchema("{}").build();
            }
            @Override public String call(String toolInput) {
                return call(toolInput, null);
            }
            @Override public String call(String toolInput, ToolContext ctx) {
                started.countDown();
                try {
                    new CountDownLatch(1).await();   // 永不返回，只能靠 cancel(true) 中断
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(new TimeoutException("simulated"));
                }
                return "unreachable";
            }
        };
        ToolCallback limited = new TimeLimitedToolCallback(blocking, Duration.ofSeconds(3));

        Thread caller = new Thread(() -> {
            try {
                limited.call("{}");
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        caller.start();
        assertTrue(started.await(2, TimeUnit.SECONDS), "委托应已开始执行");

        // 模拟回合取消：cancel(true) → delegate 抛错 → ExecutionException 分支。
        // 直接打断 caller 模拟的是 InterruptedException 分支（submit 与 get 之间被打断）。
        caller.interrupt();

        caller.join(3000);
        RuntimeException ex = (RuntimeException) thrown.get();
        assertNotNull(ex, "被打断后应抛异常");
        assertFalse(ex instanceof IllegalStateException,
                "中断/取消伴生的异常不得是 IllegalStateException（会穿透两道工具容错防线杀回合），实际=" + ex);
        assertTrue(ex instanceof ToolExecutionException,
                "中断同样应包成 ToolExecutionException 走文本回模型路径，实际=" + ex);
    }

    @Test
    void delegateExceptionIsRethrownUnchanged() {
        IllegalArgumentException original = new IllegalArgumentException("委托自己的错");
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, original), Duration.ofSeconds(5));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> limited.call("{}"));

        assertEquals("委托自己的错", ex.getMessage(),
                "委托抛的异常应原样传播，不能被包装成超时或执行失败——否则错误定位信息丢失");
    }

    @Test
    void definitionPassesThroughUnchanged() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("SlowTool", limited.getToolDefinition().name(), "本装饰器不改名，只管超时");
    }
}
