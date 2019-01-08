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
package com.alibaba.csp.sentinel.slots.statistic;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.Collection;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource id  </li>
 * <li> origin node: statistics of a cluster node from different callers/origins.</li>
 * <li> {@link DefaultNode}: statistics for specific resource name in the specific context.
 * <li> Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */

/**
 * 统计功能的Slot
 * 该Slot虽然{@link com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder}build()中不是放在执行链表的最后，但实际是最后执行的
 */
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count, Object... args)
        throws Throwable {
        try {
            /*
             * 先执行下个Slot节点的entry
             * entry()的责任链执行，StatisticSlot entry()是放在最后执行的
             */
            fireEntry(context, resourceWrapper , node, count, args);

            /*
             * 计算资源节点（DefaultNode）的线程数和通过的请求数
             * 线程数直接加1
             * 请求数需要在当前时间窗口加1，具体实现是参考ArrayMetric
             */
            node.increaseThreadNum();
            node.addPassRequest();

            if (context.getCurEntry().getOriginNode() != null) {
                /*
                 * 计算调用者节点的线程数和通过请求
                 */
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest();
            }

            /*
             * pass回调
             * 所有的回调实例都会被执行
             * 我们可以通过继承ProcessorSlotEntryCallback来实现我们的pass和block回调逻辑，通过StatisticSlotCallbackRegistry来注册回调实例
             * 由于每次request的onPass都会回调所有的已注册的callback实例，所以我们应该尽量避免callback实现太耗时的逻辑
             * 如果有特定业务的回调最好还是在回调方法根据参数做隔离
             */
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (BlockException e) {
            /*
             * 限流或者降级都会抛出BlockException
             * 此处记录异常统计和失败次数统计
             */

            context.getCurEntry().setError(e);

            // Add block count.
            node.increaseBlockQps();
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseBlockQps();
            }

            //失败回调，参考onPass回调
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            /*
             * 非BlockException异常情况统计
             */

            context.getCurEntry().setError(e);

            // Should not happen
            node.increaseExceptionQps();
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseExceptionQps();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseExceptionQps();
            }
            throw e;
        }
    }


    /**
     * 主要计算rt和减少node的线程数
     * rt的计算包含了success请求的计数和执行时间的累计，参考{@link com.alibaba.csp.sentinel.node.StatisticNode#rt(long)}
     * @param context         current {@link Context}
     * @param resourceWrapper current resource
     * @param count           tokens needed
     * @param args            parameters of the original call
     */
    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        DefaultNode node = (DefaultNode)context.getCurNode();

        if (context.getCurEntry().getError() == null) {
            long rt = TimeUtil.currentTimeMillis() - context.getCurEntry().getCreateTime();
            if (rt > Constants.TIME_DROP_VALVE) {
                rt = Constants.TIME_DROP_VALVE;
            }

            node.rt(rt);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().rt(rt);
            }

            node.decreaseThreadNum();

            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().decreaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.rt(rt);
                Constants.ENTRY_NODE.decreaseThreadNum();
            }
        } else {
            // Error may happen.
        }

        //执行exit回调，类似于entry回调（ProcessorSlotEntryCallback）
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        fireExit(context, resourceWrapper, count);
    }
}
