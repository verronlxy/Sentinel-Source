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
package com.alibaba.csp.sentinel.slots.statistic.base;

import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>
 * Basic data structure for statistic metrics.
 * </p>
 * <p>
 * Using sliding window algorithm to count data. Each bucket cover {@link #windowLengthInMs} time span,
 * and the total time span is {@link #intervalInMs}, so the total bucket count is:
 * {@link #sampleCount} = intervalInMs / windowLengthInMs.
 * </p>
 *
 * @param <T> type of data bucket.
 * @author jialiang.linjl
 * @author Eric Zhao
 * @author CarpenterLee
 */

/**
 * LeapArray将时间按固定长度切分成一个一个的window，每个window都是一个WindowWrap对象,数据则存放在WindowWrap
 * {@link #windowLengthInMs} window的单位时长，单位ms，构造方式指定
 * {@link #intervalInMs} 分割的总时长，单位ms，构造方式指定
 * {@link #sampleCount} window的数量，计算方式intervalInMs/windowLengthInMs
 * {@link #array}存储数据的数组，采用的是线程安全的AtomicReferenceArray，存储的数据是WindowWrap
 *
 * LeapArray使用 currentMs/windowLengthInMs作为窗口的id（记为timeId)
 * 通过timeId来进行寻址（定位数据的下标），寻址的方式是idx = timeId % array.length()
 */
public abstract class LeapArray<T> {

    protected int windowLengthInMs;
    protected int sampleCount;
    protected int intervalInMs;

    protected final AtomicReferenceArray<WindowWrap<T>> array;

    private final ReentrantLock updateLock = new ReentrantLock();

    /**
     * The total bucket count is: {@link #sampleCount} = intervalInSec * 1000 / windowLengthInMs.
     * @param windowLengthInMs a single window bucket's time length in milliseconds.
     * @param intervalInSec    the total time span of this {@link LeapArray} in seconds.
     */
    public LeapArray(int windowLengthInMs, int intervalInSec) {
        this.windowLengthInMs = windowLengthInMs;
        this.intervalInMs = intervalInSec * 1000;
        this.sampleCount = intervalInMs / windowLengthInMs;

        this.array = new AtomicReferenceArray<WindowWrap<T>>(sampleCount);
    }

    /**
     * Get the window at current timestamp.
     *
     * @return the window at current timestamp
     */
    public WindowWrap<T> currentWindow() {
        return currentWindow(TimeUtil.currentTimeMillis());
    }

    /**
     * Create a new bucket.
     *
     * @return the new empty bucket
     */
    public abstract T newEmptyBucket();

    /**
     * Reset current window to provided start time and reset all counters.
     *
     * @param startTime  the start time of the window
     * @param windowWrap current window
     * @return new clean window wrap
     */
    protected abstract WindowWrap<T> resetWindowTo(WindowWrap<T> windowWrap, long startTime);

    /**
     * Get window at provided timestamp.
     *
     * @param time a valid timestamp
     * @return the window at provided timestamp
     */
    /**
     * 根据时间获取数据窗口
     */
    public WindowWrap<T> currentWindow(long time) {
        //每个windowLengthInMs间隔timeId新增1
        long timeId = time / windowLengthInMs;
        // Calculate current index.
        int idx = (int)(timeId % array.length());

        // Cut the time to current window start.
        /*
         * 计算窗口的开始时间点（ms）,窗口的开始时间是windowLengthInMs的整数倍，采取较小原则，所以是time - time % windowLengthInMs
         */
        time = time - time % windowLengthInMs;

        while (true) {
            WindowWrap<T> old = array.get(idx);
            if (old == null) {
                WindowWrap<T> window = new WindowWrap<T>(windowLengthInMs, time, newEmptyBucket());
                if (array.compareAndSet(idx, null, window)) {
                    return window;
                } else {
                    Thread.yield();
                }
            } else if (time == old.windowStart()) {
                return old;
            }
            /*
             * 时间起点不对齐则重置数据窗口
             * 这里没有做二次检查，时间可能会造成多次重置数据窗口
             * 应该在lock以后，再获取 WindowWrap<T> old = array.get(idx); old，进行time == old.windowStart()
             */
            else if (time > old.windowStart()) {
                if (updateLock.tryLock()) {
                    try {
                        // if (old is deprecated) then [LOCK] resetTo currentTime.
                        return resetWindowTo(old, time);
                    } finally {
                        updateLock.unlock();
                    }
                } else {
                    Thread.yield();
                }

            }
            //正常情况下不会执行到这里
            else if (time < old.windowStart()) {
                // Cannot go through here.
                return new WindowWrap<T>(windowLengthInMs, time, newEmptyBucket());
            }
        }
    }

    /**
     * 根据时间获取前一个数据窗口
     * @param time
     * @return
     */
    public WindowWrap<T> getPreviousWindow(long time) {
        long timeId = (time - windowLengthInMs) / windowLengthInMs;
        int idx = (int)(timeId % array.length());
        time = time - windowLengthInMs;
        WindowWrap<T> wrap = array.get(idx);

        /*
         * 无效窗口
         */
        if (wrap == null || isWindowDeprecated(wrap)) {
            return null;
        }

        /*
         * 无效窗口
         */
        if (wrap.windowStart() + windowLengthInMs < (time)) {
            return null;
        }

        return wrap;
    }

    public WindowWrap<T> getPreviousWindow() {
        return getPreviousWindow(System.currentTimeMillis());
    }

    public T getWindowValue(long time) {
        long timeId = time / windowLengthInMs;
        int idx = (int)(timeId % array.length());

        WindowWrap<T> old = array.get(idx);
        if (old == null || isWindowDeprecated(old)) {
            return null;
        }

        return old.value();
    }

    /**
     * 检测窗口是否已经被启用
     * @param windowWrap
     * @return
     */
    private boolean isWindowDeprecated(WindowWrap<T> windowWrap) {
        return TimeUtil.currentTimeMillis() - windowWrap.windowStart() >= intervalInMs;
    }

    public List<WindowWrap<T>> list() {
        List<WindowWrap<T>> result = new ArrayList<WindowWrap<T>>();

        for (int i = 0; i < array.length(); i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(windowWrap)) {
                continue;
            }
            result.add(windowWrap);
        }

        return result;
    }

    public List<T> values() {
        List<T> result = new ArrayList<T>();

        for (int i = 0; i < array.length(); i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(windowWrap)) {
                continue;
            }
            result.add(windowWrap.value());
        }
        return result;
    }
}
