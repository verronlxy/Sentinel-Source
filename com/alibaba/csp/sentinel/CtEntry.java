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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;

/**
 * Linked entry within current context.
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
class CtEntry extends Entry {

    protected Entry parent = null;
    protected Entry child = null;

    protected ProcessorSlot<Object> chain;
    protected Context context;

    /**
     * 创建CtEntry对象主要做了两件事
     * 1、初始化上下文context，执行链chain
     * 2、更新entry链（成员变量parent，child）
     * 3、设置context的当前entry
     * @param resourceWrapper
     * @param chain
     * @param context
     */
    CtEntry(ResourceWrapper resourceWrapper, ProcessorSlot<Object> chain, Context context) {
        super(resourceWrapper);
        this.chain = chain;
        this.context = context;
        //更新调用链
        setUpEntryFor(context);
    }

    private void setUpEntryFor(Context context) {
        // The entry should not be associated to NullContext.
        if (context instanceof NullContext) {
            return;
        }
        //获取context的当前入口
        this.parent = context.getCurEntry();
        if (parent != null) {
            ((CtEntry)parent).child = this;
        }
        /*
         *更新context的当前entry对象
         *资源入口（entry）是可以嵌套的，所以使用链表的结构来存储entry对象
         *可以通过变量parent或者child变量获取资源入口的链路
         */
        context.setCurEntry(this);
    }

    /**
     * 退出逻辑
     * @param count tokens to release.
     * @param args
     * @throws ErrorEntryFreeException
     */
    @Override
    public void exit(int count, Object... args) throws ErrorEntryFreeException {
        trueExit(count, args);
    }

    /**
     * 退出资源
     *
     * @param context
     * @param count
     * @param args
     * @throws ErrorEntryFreeException
     */
    protected void exitForContext(Context context, int count, Object... args) throws ErrorEntryFreeException {
        if (context != null) {
            // Null context should exit without clean-up.
            if (context instanceof NullContext) {
                return;
            }
            /*
             * 如果当前entry不是context的curEntry
             * 清除entry链并抛出异常
             * 清除操作其实就是调用entry的exit方法
             */
            if (context.getCurEntry() != this) {
                String curEntryNameInContext = context.getCurEntry() == null ? null : context.getCurEntry().getResourceWrapper().getName();
                // Clean previous call stack.
                CtEntry e = (CtEntry)context.getCurEntry();
                while (e != null) {
                    e.exit(count, args);
                    e = (CtEntry)e.parent;
                }
                String errorMessage = String.format("The order of entry exit can't be paired with the order of entry"
                    + ", current entry in context: <%s>, but expected: <%s>", curEntryNameInContext, resourceWrapper.getName());
                throw new ErrorEntryFreeException(errorMessage);
            } else {
                /*
                 * 如果执行链为空
                 * 会逐个调用Slot的exit()
                 * 默认只有StatisticSlot实现了exit()
                 */
                if (chain != null) {
                    chain.exit(context, resourceWrapper, count, args);
                }

                // Restore the call stack.
                context.setCurEntry(parent);
                if (parent != null) {
                    ((CtEntry)parent).child = null;
                }
                //如果不存在parent就会销毁context
                if (parent == null) {
                    // Default context (auto entered) will be exited automatically.
                    if (ContextUtil.isDefaultContext(context)) {
                        ContextUtil.exit();
                    }
                }
                // Clean the reference of context in current entry to avoid duplicate exit.
                //set context=null
                clearEntryContext();
            }
        }
    }

    protected void clearEntryContext() {
        this.context = null;
    }

    /**
     * 执行退出操作并返回上一级entry对象parent
     * @param count tokens to release.
     * @param args
     * @return
     * @throws ErrorEntryFreeException
     */
    @Override
    protected Entry trueExit(int count, Object... args) throws ErrorEntryFreeException {
        exitForContext(context, count, args);

        return parent;
    }

    @Override
    public Node getLastNode() {
        return parent == null ? null : parent.getCurNode();
    }
}