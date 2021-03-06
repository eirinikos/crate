/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.jobs.transport;

import io.crate.execution.jobs.JobContextService;
import io.crate.execution.jobs.kill.KillJobsRequest;
import io.crate.execution.jobs.kill.TransportKillJobsNodeAction;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.transport.TransportConnectionListener;
import org.elasticsearch.transport.TransportService;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * service that listens to node-disconnected-events and kills jobContexts that were started by the nodes that got disconnected
 */
@Singleton
public class NodeDisconnectJobMonitorService extends AbstractLifecycleComponent implements TransportConnectionListener {

    private final JobContextService jobContextService;
    private final TransportService transportService;

    private final TransportKillJobsNodeAction killJobsNodeAction;
    private static final Logger LOGGER = Loggers.getLogger(NodeDisconnectJobMonitorService.class);

    @Inject
    public NodeDisconnectJobMonitorService(Settings settings,
                                           JobContextService jobContextService,
                                           TransportService transportService,
                                           TransportKillJobsNodeAction killJobsNodeAction) {
        super(settings);
        this.jobContextService = jobContextService;
        this.transportService = transportService;
        this.killJobsNodeAction = killJobsNodeAction;
    }


    @Override
    protected void doStart() {
        transportService.addConnectionListener(this);
    }

    @Override
    protected void doStop() {
        transportService.removeConnectionListener(this);
    }

    @Override
    protected void doClose() {
    }

    @Override
    public void onNodeConnected(DiscoveryNode node) {
    }

    @Override
    public void onNodeDisconnected(final DiscoveryNode node) {
        killJobsCoordinatedBy(node);
        broadcastKillToParticipatingNodes(node);

    }

    /**
     * Broadcast the kill if *this* node is the coordinator and a participating node died
     * The information which nodes are participating is only available on the coordinator, so other nodes
     * can not kill the jobs on their own.
     *
     * <pre>
     *              n1                      n2                  n3
     *               |                      |                   |
     *           startJob 1 (n1,n2,n3)      |                   |
     *               |                      |                   |
     *               |                    *dies*                |
     *               |                                          |
     *           onNodeDisc(n2)                            onNodeDisc(n2)
     *            broadcast kill job1                   does not know which jobs involve n2
     *                  |
     *      kill job1 <-+---------------------------------->  kill job1
     *
     * </pre>
     */
    private void broadcastKillToParticipatingNodes(DiscoveryNode deadNode) {
        List<UUID> affectedJobs = jobContextService
            .getJobIdsByParticipatingNodes(deadNode.getId()).collect(Collectors.toList());
        if (affectedJobs.isEmpty()) {
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Broadcasting kill for {} jobs because they involved disconnected node={}",
                affectedJobs.size(),
                deadNode.getId());
        }
        List<String> excludedNodeIds = Collections.singletonList(deadNode.getId());
        killJobsNodeAction.broadcast(new KillJobsRequest(affectedJobs), new ActionListener<Long>() {
            @Override
            public void onResponse(Long numKilled) {
            }

            @Override
            public void onFailure(Exception e) {
                LOGGER.warn("failed to send kill request to nodes");
            }
        }, excludedNodeIds);
    }

    /**
     * Immediately kills all jobs that were initiated by the disconnected node.
     * It is not possible to send results to the disconnected node.
     * <pre>
     *
     *              n1                      n2                  n3
     *               |                      |                   |
     *           startJob 1 (n1,n2,n3)      |                   |
     *               |                      |                   |
     *              *dies*                  |                   |
     *                                   onNodeDisc(n1)      onNodeDisc(n1)
     *                                    killJob 1            killJob1
     * </pre>
     */
    private void killJobsCoordinatedBy(DiscoveryNode deadNode) {
        List<UUID> jobsStartedByDeadNode = jobContextService
            .getJobIdsByCoordinatorNode(deadNode.getId()).collect(Collectors.toList());
        if (jobsStartedByDeadNode.isEmpty()) {
            return;
        }
        jobContextService.killJobs(jobsStartedByDeadNode);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Killed {} jobs started by disconnected node={}", jobsStartedByDeadNode.size(), deadNode.getId());
        }
    }
}
