/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * Degrade is used when the resources are in an unstable state, these resources
 * will be degraded within the next defined time window. There are two ways to
 * measure whether a resource is stable or not:
 * </p>
 * <ul>
 * <li>
 * Average response time ({@code DEGRADE_GRADE_RT}): When
 * the average RT exceeds the threshold ('count' in 'DegradeRule', in milliseconds), the
 * resource enters a quasi-degraded state. If the RT of next coming 5
 * requests still exceed this threshold, this resource will be downgraded, which
 * means that in the next time window (defined in 'timeWindow', in seconds) all the
 * access to this resource will be blocked.
 * </li>
 * <li>
 * Exception ratio: When the ratio of exception count per second and the
 * success qps exceeds the threshold, access to the resource will be blocked in
 * the coming window.
 * </li>
 * </ul>
 *
 * @author jialiang.linjl
 */
public class DegradeRule extends AbstractRule {

    private static final int RT_MAX_EXCEED_N = 5;

    private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));

    public DegradeRule() {}

    public DegradeRule(String resourceName) {
        setResource(resourceName);
    }

    /**
     * RT threshold or exception ratio threshold count.
     */
    private double count;

    /**
     * Degrade recover timeout (in seconds) when degradation occurs.
     */
    private int timeWindow;

    /**
     * Degrade strategy (0: average RT, 1: exception ratio).
     */
    private int grade = RuleConstant.DEGRADE_GRADE_RT;

    private volatile boolean cut = false;

    public int getGrade() {
        return grade;
    }

    public DegradeRule setGrade(int grade) {
        this.grade = grade;
        return this;
    }

    private AtomicLong passCount = new AtomicLong(0);

    private final Object lock = new Object();

    public double getCount() {
        return count;
    }

    public DegradeRule setCount(double count) {
        this.count = count;
        return this;
    }

    public boolean isCut() {
        return cut;
    }

    private void setCut(boolean cut) {
        this.cut = cut;
    }

    public AtomicLong getPassCount() {
        return passCount;
    }

    public int getTimeWindow() {
        return timeWindow;
    }

    public DegradeRule setTimeWindow(int timeWindow) {
        this.timeWindow = timeWindow;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DegradeRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        DegradeRule that = (DegradeRule)o;

        if (count != that.count) {
            return false;
        }
        if (timeWindow != that.timeWindow) {
            return false;
        }
        if (grade != that.grade) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + new Double(count).hashCode();
        result = 31 * result + timeWindow;
        result = 31 * result + grade;
        return result;
    }

    /**
     * 检查熔断条件
     *
     * @param context current {@link Context}
     * @param node    current {@link com.alibaba.csp.sentinel.node.Node}
     * @param acquireCount
     * @param args    arguments of the original invocation.
     * @return
     */
    @Override
    public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
        /*
         * 检测降级开关已打开，cut为true时，降级已打开，对该资源的请求将被拒绝
         */
        if (cut) {
            return false;
        }

        /*
         * 获取节点的统计信息
         */
        ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
        if (clusterNode == null) {
            return true;
        }
        /*
         * 降级策略:平均时长降级
         * 如果连续>=RT_MAX_EXCEED_N（默认5）次出现avgRt>count，则会触发降级策略
         * 实际如果avgRt>count了，正常来讲接下来的5次也是满足avgRt>count，也就会触发降级测了，以下这段代码的意义就不是很大了
         * if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
                return true;
            }
         */
        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            double rt = clusterNode.avgRt();
            if (rt < this.count) {
                passCount.set(0);
                return true;
            }

            // Sentinel will degrade the service only if count exceeds.
            if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
                return true;
            }
        }
         /*
         * 降级策略:异常比率降级
         * 如果exception / success < count，则会触发降级策略
         */
        else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
            double exception = clusterNode.exceptionQps();
            double success = clusterNode.successQps();
            long total = clusterNode.totalQps();
            // if total qps less than RT_MAX_EXCEED_N, pass.
            if (total < RT_MAX_EXCEED_N) {
                return true;
            }

            /**
             * 此处应该有误？success应该不包含exception，如果
             * success的计算参考
             * {@link com.alibaba.csp.sentinel.slots.statistic.StatisticSlot#exit(Context, ResourceWrapper, int, Object...)}
             * {@link com.alibaba.csp.sentinel.node.StatisticNode#rt(long)}
             */
            double realSuccess = success - exception;
            if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) {
                return true;
            }

            if (exception / success < count) {
                return true;
            }
        }

        /*
         * 降级策略:异常数降级
         * 如果exception < count，则会触发降级策略
         * exception是发送业务异常时才会统计，BlockException（限流、降级）不会纳入统计
         */
        else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            double exception = clusterNode.totalException();
            if (exception < count) {
                return true;
            }
        }

        /*
         * 降级实施逻辑
         * 触发降级规则后，在timeWindow时间内会保持cut开关为true，并设置this.passCount为0
         * resetTask会在timeWindow后定时reset cut状态，置cut为false,关闭降级
         *
         */
        synchronized (lock) {
            if (!cut) {
                // Automatically degrade.
                cut = true;
                ResetTask resetTask = new ResetTask(this);
                pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
            }
            return false;
        }
    }

    @Override
    public String toString() {
        return "DegradeRule{" +
            "resource=" + getResource() +
            ", grade=" + grade +
            ", count=" + count +
            ", limitApp=" + getLimitApp() +
            ", timeWindow=" + timeWindow +
            "}";
    }

    private static final class ResetTask implements Runnable {

        private DegradeRule rule;

        ResetTask(DegradeRule rule) {
            this.rule = rule;
        }

        @Override
        public void run() {
            rule.getPassCount().set(0);
            rule.setCut(false);
        }
    }
}

