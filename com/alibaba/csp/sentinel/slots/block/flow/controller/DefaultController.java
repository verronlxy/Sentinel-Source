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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.Controller;

/**
 * @author jialiang.linjl
 */
public class DefaultController implements Controller {

    double count = 0;
    int grade = 0;

    public DefaultController(double count, int grade) {
        super();
        this.count = count;
        this.grade = grade;
    }

    /**
     * 控制请求是否通过，通过true，否则false，根据StatisticNode统计信息计算
     * 计算qps或者单位时间的线程数量是不是超过限流的阀值
     * @param node
     * @param acquireCount
     * @return
     */
    @Override
    public boolean canPass(Node node, int acquireCount) {
        //已经通过的threadCount或者qps
        int curCount = avgUsedTokens(node);
        if (curCount + acquireCount > count) {
            return false;
        }

        return true;
    }

    private int avgUsedTokens(Node node) {
        if (node == null) {
            return -1;
        }
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)node.passQps();
    }

}
