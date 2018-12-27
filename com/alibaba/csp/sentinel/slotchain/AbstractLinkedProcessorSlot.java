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
package com.alibaba.csp.sentinel.slotchain;

import com.alibaba.csp.sentinel.context.Context;

/**
 * AbstractLinkedProcessorSlot是一个链表对象
 * 内部维护了一个指向下一个AbstractLinkedProcessorSlot节点的next变量
 * 链表的节点是统计，限流，熔断等功能的具体Slot
 * 通过next.transformEntry(context, resourceWrapper, obj, count, args)执行具体Slot的entry，实现统计、限流、熔断等逻辑。
 * 通过fireEntry来遍历具体Slot节点
 * @author qinan.qn
 * @author jialiang.linjl
 */
public abstract class AbstractLinkedProcessorSlot<T> implements ProcessorSlot<T> {


    private AbstractLinkedProcessorSlot<?> next = null;

    @Override
    public void fireEntry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, Object... args)
        throws Throwable {
        if (next != null) {
            next.transformEntry(context, resourceWrapper, obj, count, args);
        }
    }

    @SuppressWarnings("unchecked")
    void transformEntry(Context context, ResourceWrapper resourceWrapper, Object o, int count, Object... args)
        throws Throwable {
        T t = (T)o;
        entry(context, resourceWrapper, t, count, args);
    }

    @Override
    public void fireExit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        if (next != null) {
            next.exit(context, resourceWrapper, count, args);
        }
    }

    public AbstractLinkedProcessorSlot<?> getNext() {
        return next;
    }

    public void setNext(AbstractLinkedProcessorSlot<?> next) {
        this.next = next;
    }

}
