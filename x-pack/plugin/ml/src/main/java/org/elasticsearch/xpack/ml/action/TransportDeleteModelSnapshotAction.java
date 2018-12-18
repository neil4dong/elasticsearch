/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.action.DeleteModelSnapshotAction;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.job.persistence.JobDataDeleter;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsProvider;
import org.elasticsearch.xpack.ml.notifications.Auditor;

import java.util.Collections;
import java.util.List;

public class TransportDeleteModelSnapshotAction extends HandledTransportAction<DeleteModelSnapshotAction.Request,
    AcknowledgedResponse> {

    private final Client client;
    private final JobManager jobManager;
    private final JobResultsProvider jobResultsProvider;
    private final Auditor auditor;

    @Inject
    public TransportDeleteModelSnapshotAction(Settings settings, TransportService transportService, ThreadPool threadPool,
                                              ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                              JobManager jobManager, JobResultsProvider jobResultsProvider, Client client,
                                              Auditor auditor) {
        super(settings, DeleteModelSnapshotAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                DeleteModelSnapshotAction.Request::new);
        this.client = client;
        this.jobManager = jobManager;
        this.jobResultsProvider = jobResultsProvider;
        this.auditor = auditor;
    }

    @Override
    protected void doExecute(DeleteModelSnapshotAction.Request request, ActionListener<AcknowledgedResponse> listener) {
        // Verify the snapshot exists
        jobResultsProvider.modelSnapshots(
                request.getJobId(), 0, 1, null, null, null, true, request.getSnapshotId(),
                page -> {
                    List<ModelSnapshot> deleteCandidates = page.results();
                    if (deleteCandidates.size() > 1) {
                        logger.warn("More than one model found for [job_id: " + request.getJobId()
                                + ", snapshot_id: " + request.getSnapshotId() + "] tuple.");
                    }

                    if (deleteCandidates.isEmpty()) {
                        listener.onFailure(new ResourceNotFoundException(Messages.getMessage(Messages.REST_NO_SUCH_MODEL_SNAPSHOT,
                                request.getSnapshotId(), request.getJobId())));
                        return;
                    }
                    ModelSnapshot deleteCandidate = deleteCandidates.get(0);

                    // Verify the snapshot is not being used
                    jobManager.getJob(request.getJobId(), ActionListener.wrap(
                            job -> {
                                String currentModelInUse = job.getModelSnapshotId();
                                if (currentModelInUse != null && currentModelInUse.equals(request.getSnapshotId())) {
                                    listener.onFailure(
                                            new IllegalArgumentException(Messages.getMessage(Messages.REST_CANNOT_DELETE_HIGHEST_PRIORITY,
                                            request.getSnapshotId(), request.getJobId())));
                                    return;
                                }

                                // Delete the snapshot and any associated state files
                                JobDataDeleter deleter = new JobDataDeleter(client, request.getJobId());
                                deleter.deleteModelSnapshots(Collections.singletonList(deleteCandidate),
                                        new ActionListener<BulkResponse>() {
                                            @Override
                                            public void onResponse(BulkResponse bulkResponse) {
                                                String msg = Messages.getMessage(Messages.JOB_AUDIT_SNAPSHOT_DELETED,
                                                        deleteCandidate.getSnapshotId(), deleteCandidate.getDescription());

                                                auditor.info(request.getJobId(), msg);
                                                logger.debug("[{}] {}", request.getJobId(), msg);
                                                // We don't care about the bulk response, just that it succeeded
                                                listener.onResponse(new AcknowledgedResponse(true));
                                            }

                                            @Override
                                            public void onFailure(Exception e) {
                                                listener.onFailure(e);
                                            }
                                        });

                            },
                            listener::onFailure
                    ));
                }, listener::onFailure);
    }
}
