/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.workflowpublication.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.refactoring.job.MoveRequest;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.workflowpublication.PublicationWorkflow;
import org.xwiki.workflowpublication.WorkflowConfigManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;

/**
 * Event listener to listen to document rename events and update the target of the draft according to the new location.
 * Also rename the children if the Preserve children option is checked in the Move/Rename wizard.
 * Currently, the action taken for the events is:
 *
 * <ul>
 *   <li> if the event is a rename/move and the document is a target, we instead update the targetDocument attribute
 *     in the workflow object to point to itself, and also update the targetDocument of the corresponding draft.
 *     Depending on the configuration of the workflow, drafts can also be moved automatically (see below).
 *   <li> if the event is a rename/move of a draft, we restore its original workflow state.
 *    Otherwise we note the new target location in a separate attribute of the workflow and perform the move when the
 *    document gets published.
 *    Depending on the configuration of the workflow, the target can also be moved automatically (see below).
 * </ul>
 * <p>
 * Depending on the configuration of the option WF_MOVE_STRATEGY_FIELD_NAME in the workflow configuration, the
 * listener can either :
 * <ul>
 *   <li>Move the equivalent documents of the document being moved, whatever this document is (draft or target)</li>
 *   <li>Only move the drafts corresponding to a target that is being moved</li>
 *   <li>Only move the target corresponding to a draft that is being moved</li>
 *   <li>Only update the target attribute of the draft being moved when the target is not published yet</li>
 *   <li>Do nothing</li>
 * </ul>
 * When the move of an equivalent document is attempted, the listener will try to compute the new location of the
 * equivalent document based on a diff between the old location and the new location of the document being moved. If
 * this computation fails, no move is attempted, and the user is informed through the move logs.
 * When performing such moves, the following parameters are checked :
 * <ul>
 *   <li>Verify that the equivalent document has not been moved already.</li>
 *   <li>Verify that the user performing the move has edit rights on the target space.</li>
 *   <li>Verify that no document already exists in the new location.</li>
 * </ul>
 *
 * @version $Id$
 * @since 1.9
 */
@Component
@Named("PublicationWorkflowRenameListener")
@Singleton
public class PublicationWorkflowRenameListener implements EventListener
{

    private static final String WORKFLOW_EQUIVALENT = "workflowEquivalent";

    private static final String TARGET = "target";

    private static final String MOVE_STRATEGY_DO_NOTHING = "doNothing";

    private static final String MOVE_STRATEGY_MOVE_TARGET_IF_UNPUBLISHED = "moveTargetIfUnpublished";

    private static final String MOVE_STRATEGY_MOVE_TARGET = "moveTarget";

    private static final String MOVE_STRATEGY_MOVE_DRAFTS = "moveDrafts";

    private static final String MOVE_STRATEGY_MOVE_ALL = "moveAll";

    private static final String WF_MOVE_STRATEGY_FIELD_NAME = "moveStrategy";

    private final List<Event> eventsList = Collections.singletonList(new DocumentRenamedEvent());

    @Inject
    private WorkflowConfigManager configManager;

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;

    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> stringResolver;

    @Inject
    private QueryManager queryManager;

    /**
     * Reference string serializer to be used to store the workflow target.
     */
    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> compactWikiSerializer;

    /**
     * Used to check access rights when moving automatically equivalent documents.
     */
    @Inject
    private AuthorizationManager authorizationManager;

    @Inject
    private PublicationWorkflow publicationWorkflow;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.observation.EventListener#getEvents()
     */
    public List<Event> getEvents()
    {
        return eventsList;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.xwiki.observation.EventListener#getName()
     */
    public String getName()
    {
        return "PublicationWorkflowRenameListener";
    }

    /**
     * (the include mace) {@inheritDoc}
     *
     * @see EventListener#onEvent(Event, Object, Object)
     */
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiContext context = contextProvider.get();
        String wikiId = context.getWikiId();

        try {
            MoveRequest moveRequest = (MoveRequest) data;
            DocumentReference workflowSourceRef =
                (DocumentReference) moveRequest.getEntityReferences().stream().findFirst().orElse(null);
            DocumentReference workflowDestinationRef = (DocumentReference) moveRequest.getDestination();

            // Set the context wiki to current wiki as the DocumentRenamedEvent is executed with the main wiki context.
            context.setWikiId(Objects.requireNonNull(workflowSourceRef).getWikiReference().getName());

            XWikiDocument workflowDoc = getWorkflowDocument(workflowSourceRef, workflowDestinationRef, context);

            if (publicationWorkflow.isWorkflowDocument(workflowDoc, context)) {
                // Get the moving strategy.
                BaseObject workflowConfig = configManager.getWorkflowConfigForWorkflowDoc(workflowDoc, context);
                String moveStrategy = workflowConfig.getStringValue(WF_MOVE_STRATEGY_FIELD_NAME);
                if (shouldProcessMoveStrategy(moveStrategy)) {
                    DocumentRenamedEvent documentRenamedEvent = (DocumentRenamedEvent) event;
                    DocumentReference currentSourceRef = documentRenamedEvent.getSourceReference();
                    DocumentReference currentTargetRef = documentRenamedEvent.getTargetReference();

                    boolean isTarget = workflowDoc.getIntValue("istarget") == 1;

                    switch (moveStrategy) {
                        case MOVE_STRATEGY_MOVE_TARGET:
                            if (!isTarget) {
                                handlePublished(workflowDoc, workflowSourceRef, currentSourceRef, currentTargetRef,
                                    true, context);
                            }
                        case MOVE_STRATEGY_MOVE_ALL:
                            handlePublished(workflowDoc, workflowSourceRef, currentSourceRef, currentTargetRef,
                                !isTarget, context);
                            break;
                        case MOVE_STRATEGY_MOVE_DRAFTS:
                            if (isTarget) {
                                handlePublished(workflowDoc, workflowSourceRef, currentSourceRef, currentTargetRef,
                                    false, context);
                            }
                            break;
                        case MOVE_STRATEGY_MOVE_TARGET_IF_UNPUBLISHED:
                            if (!isTarget) {
                                handleUnPublished(workflowDoc, workflowSourceRef, currentSourceRef, currentTargetRef,
                                    context);
                            }
                            break;
                    }
                }
            }
        } catch (XWikiException e) {
            throw new RuntimeException(e);
        } finally {
            // Set back the context wiki to original (main).
            context.setWikiId(wikiId);
        }
    }

    private void handleUnPublished(XWikiDocument workflowDoc, DocumentReference workflowSourceRef,
        DocumentReference currentSourceRef, DocumentReference currentTargetRef, XWikiContext context)
        throws XWikiException
    {
        BaseObject workflowObj =
            workflowDoc.getXObject(currentReferenceEntityResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        String workflowEquivalent = workflowObj.getStringValue(TARGET);
        DocumentReference workflowEquivalentRef = stringResolver.resolve(workflowEquivalent);

        if (!context.getWiki().exists(workflowEquivalentRef, context)) {
            DocumentReference oldEquivalentRef =
                computeEquivalentDocRef(workflowSourceRef, workflowEquivalentRef,
                    currentSourceRef, context);
            if (oldEquivalentRef.equals(workflowEquivalentRef)) {
                DocumentReference newEquivalentRef =
                    computeEquivalentDocRef(currentSourceRef, currentTargetRef,
                        oldEquivalentRef,
                        context);
                maybeRenameEquivalentDocument(workflowObj, null, workflowEquivalentRef, oldEquivalentRef,
                    newEquivalentRef, currentTargetRef, false, true, context);
            }
        }
    }

    private void handlePublished(XWikiDocument workflowDoc, DocumentReference workflowSourceRef,
        DocumentReference currentSourceRef, DocumentReference currentTargetRef, boolean isEquivalentTarget,
        XWikiContext context)
        throws XWikiException
    {
        BaseObject workflowObj =
            workflowDoc.getXObject(currentReferenceEntityResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        String workflowEquivalent = getOrSetWorkflowEquivalent(workflowObj, workflowSourceRef, isEquivalentTarget,
            context);
        DocumentReference workflowEquivalentRef = stringResolver.resolve(workflowEquivalent);

        DocumentReference oldEquivalentRef =
            computeEquivalentDocRef(workflowSourceRef, workflowEquivalentRef, currentSourceRef, context);
        DocumentReference newEquivalentRef =
            computeEquivalentDocRef(currentSourceRef, currentTargetRef, oldEquivalentRef, context);

        maybeRenameEquivalentDocument(workflowObj, workflowDoc.getTitle(), workflowEquivalentRef, oldEquivalentRef,
            newEquivalentRef, currentTargetRef, true, isEquivalentTarget, context);
    }

    private void maybeRenameEquivalentDocument(BaseObject workflowObj, String workflowDocTitle,
        DocumentReference workflowEquivalentRef, DocumentReference oldEquivalentRef, DocumentReference newEquivalentRef,
        DocumentReference currentTargetRef, boolean isPublished, boolean isEquivalentTarget, XWikiContext context)
        throws XWikiException
    {
        if (oldEquivalentRef.equals(workflowEquivalentRef)) {
            XWikiDocument oldEquivalentDoc = context.getWiki().getDocument(oldEquivalentRef, context);
            oldEquivalentDoc.setTitle(workflowDocTitle);
            DocumentReference newWorkFlowTargetRef = currentTargetRef;
            if (isEquivalentTarget) {
                newWorkFlowTargetRef = newEquivalentRef;
            }
            workflowObj.setStringValue(TARGET, compactWikiSerializer.serialize(newWorkFlowTargetRef));
            BaseObject equivalentWorkflowObj =
                oldEquivalentDoc.getXObject(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS);
            equivalentWorkflowObj.setStringValue(TARGET, compactWikiSerializer.serialize(newWorkFlowTargetRef));
        }

        if (isPublished) {
            renameEquivalentDocument(oldEquivalentRef, newEquivalentRef, context);
        }
    }

    /**
     * Retrieves the workflow equivalent from the context, or sets it if not already present.
     * <p>
     * If the workflow equivalent is not already stored in the context, this method determines the value based on
     * whether the document is a target or not. If the document is a target, it fetches the value from the workflow
     * object using the {@code TARGET} field. If it's not a target, the method calls
     * {@link #getEquivalentDraft(DocumentReference)} to compute the equivalent draft. The determined value is then
     * stored in the context and returned.
     *
     * @param workflowObj the workflow object containing workflow metadata
     * @param workflowSourceRef the reference to the source document of the workflow
     * @param isTarget {@code true} if the document is a target, {@code false} if it's a draft
     * @param context the current XWiki context, which may hold the workflow equivalent
     * @return the workflow equivalent, either fetched from the context or computed and stored in the context
     */
    private String getOrSetWorkflowEquivalent(BaseObject workflowObj, DocumentReference workflowSourceRef,
        boolean isTarget, XWikiContext context)
    {
        if (StringUtils.isEmpty((String) context.get(WORKFLOW_EQUIVALENT))) {
            String workflowEquivalent;
            if (isTarget) {
                workflowEquivalent = workflowObj.getStringValue(TARGET);
            } else {
                workflowEquivalent = getEquivalentDraft(workflowSourceRef);
            }
            context.put(WORKFLOW_EQUIVALENT, workflowEquivalent);
            return workflowEquivalent;
        }
        return (String) context.get(WORKFLOW_EQUIVALENT);
    }

    /**
     * Retrieves the workflow document based on the source and destination references. This method first attempts to
     * fetch the workflow document using the source reference. If the document is not identified as a workflow document
     * (e.g., after it has been renamed), the method fetches the document using the destination reference instead.
     *
     * @param workflowSourceDocRef the reference to the source document of the workflow
     * @param workflowDestinationDocRef the reference to the destination document of the workflow
     * @param context the current XWiki context used to retrieve documents and other wiki-specific data
     * @return the workflow document, either from the source or destination reference
     * @throws XWikiException if an error occurs while retrieving the document
     */
    private XWikiDocument getWorkflowDocument(DocumentReference workflowSourceDocRef,
        DocumentReference workflowDestinationDocRef, XWikiContext context) throws XWikiException
    {
        XWiki xwiki = context.getWiki();
        XWikiDocument workflowDoc = xwiki.getDocument(workflowSourceDocRef, context);
        // After the workflow doc's rename, we should consider the new document to get information for
        // children's rename.
        if (!publicationWorkflow.isWorkflowDocument(workflowDoc, context)) {
            workflowDoc = xwiki.getDocument(workflowDestinationDocRef, context);
        }
        return workflowDoc;
    }

    private boolean shouldProcessMoveStrategy(String moveStrategy)
    {
        return StringUtils.isNotEmpty(moveStrategy) && !MOVE_STRATEGY_DO_NOTHING.equals(moveStrategy);
    }

    /**
     * Renames or moves a document from an old document reference to a new document reference under certain conditions:
     * <ul>
     *   <li>The old document must exist.</li>
     *   <li>The new document must not already exist to avoid overwriting.</li>
     *   <li>The user performing the operation must have edit rights on the new document reference.</li>
     * </ul>
     * <p>
     * If any of these conditions are not met, the document is not moved and appropriate warnings or info messages are logged.
     *
     * @param oldDocRef the reference to the existing document to be renamed or moved
     * @param newDocRef the reference to the new location for the document
     * @param context the XWikiContext, providing context and execution environment information for the operation
     * @throws XWikiException If an error occurs during the rename operation
     */
    private void renameEquivalentDocument(DocumentReference oldDocRef, DocumentReference newDocRef,
        XWikiContext context)
        throws XWikiException
    {
        XWiki xwiki = context.getWiki();
        // Check if the old equivalent document exists.
        if (!xwiki.exists(oldDocRef, context)) {
            logger.info("The equivalent document [{}] does not exist anymore", oldDocRef);
            return;
        }

        // Check if the new equivalent document exists.
        if (xwiki.exists(newDocRef, context)) {
            logger.warn("Cannot move [{}] to destination [{}] as a document already exists on this location",
                oldDocRef, newDocRef);
            return;
        }

        // Check if the user performing the move has edit rights on the new document reference.
        if (!authorizationManager.hasAccess(Right.EDIT, context.getUserReference(), newDocRef)) {
            logger.warn("Cannot move [{}] to destination [{}] as the current user [{}] has no edit rights on the "
                    + "destination document", oldDocRef, newDocRef,
                context.getUserReference());
            return;
        }

        xwiki.renameDocument(oldDocRef, newDocRef, true, Collections.emptyList(), Collections.emptyList(), context);
    }

    /**
     * Computes the new equivalent document reference based on the differences between the old and new references of the
     * current document. This method attempts to generate a new reference for an equivalent document by applying the
     * same changes that were made to the current document reference to the equivalent document reference.
     * <p>
     * If the reference cannot be computed (e.g., due to patch failure), the old equivalent document reference is
     * returned.
     * <p>
     * The method operates by creating a diff between the old and new current document references and then attempts to
     * apply this diff to the old equivalent document reference. If the patch applies correctly and results in a valid
     * document name, the new equivalent reference is returned.
     *
     * @param oldCurrentDocRef the reference to the current document before it was moved
     * @param newCurrentDocRef the reference to the current document after it was moved
     * @param oldEquivalentDocRef the reference to the equivalent document before the move
     * @param context the current XWikiContext, providing the execution environment and wiki information
     * @return the new equivalent document reference if successfully computed; otherwise, the old equivalent document
     *     reference
     */
    private DocumentReference computeEquivalentDocRef(DocumentReference oldCurrentDocRef,
        DocumentReference newCurrentDocRef, DocumentReference oldEquivalentDocRef, XWikiContext context)
    {
        // Compare the differences between the original draft and target references
        // One option would be to do a three-way merge with :
        // * Previous : the old draft name
        // * Current : the old target name
        // * New : the new draft name
        // However this option does not work well with small strings, such as serialized document references.
        // Instead, we will be creating a diff based on the old draft reference and the new draft reference,
        // and then we'll try to apply this diff to the target reference. If the diff applies correctly,
        // and if it corresponds to a valid document name, we can continue. Else, we abort.

        // Start by splitting the different document references into an array of strings, which will be easier
        // to diff.
        List<String> oldCurrentSplittedReference = createSplitReference(oldCurrentDocRef);
        List<String> newCurrentSplittedReference = createSplitReference(newCurrentDocRef);
        List<String> oldEquivalentSplittedReference = createSplitReference(oldEquivalentDocRef);

        // Create the patch and try to apply it.
        Patch<String> patch = DiffUtils.diff(oldCurrentSplittedReference, newCurrentSplittedReference);
        List<String> newEquivalentSplittedReference;
        try {
            newEquivalentSplittedReference = DiffUtils.patch(oldEquivalentSplittedReference, patch);
        } catch (PatchFailedException e) {
            logger.warn("Unable to compute new location for the document [{}]", oldEquivalentDocRef);
            logger.debug("Error when applying patch : [{}]", ExceptionUtils.getRootCause(e), e);
            return oldEquivalentDocRef;
        }

        // Reconstruct the new equivalent reference.
        String newEquivalentName = newEquivalentSplittedReference.get(newEquivalentSplittedReference.size() - 1);
        newEquivalentSplittedReference.remove(newEquivalentSplittedReference.size() - 1);

        return new DocumentReference(context.getWikiId(), newEquivalentSplittedReference, newEquivalentName);
    }

    /**
     * From the given document reference, create a list that contains the reversed reference chain of this document,
     * with the exclusion of the wiki reference.
     * <p>
     * Example : with a document "A.B.C.D.WebHome", the list will be ['A', 'B', 'C', 'D', 'WebHome']
     *
     * @param reference the reference to use
     * @return the splitted reference list
     */
    private List<String> createSplitReference(DocumentReference reference)
    {
        List<String> splittedReference = new ArrayList<>();

        for (EntityReference entity : reference.getReversedReferenceChain()) {
            if (!(entity instanceof WikiReference)) {
                splittedReference.add(entity.getName());
            }
        }

        return splittedReference;
    }

    /**
     * helper to look up either the draft or the target document. in either case try to find the page(s) that have a
     * workflow document which &quot;target&quot; attribute points to given target name.
     *
     * @param targetRef the serialized target document name
     * @return a list of documentReferences (as strings); should usually contain at most one element
     */
    private String getEquivalentDraft(DocumentReference targetRef)
    {
        // Searching for the draft.
        Map<String, Object> queryParams = new HashMap<>();
        String workflowsQuery =
            "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget "
                + "where obj.className = :className and obj.id = target.id.id and target.id.name = 'target' and "
                + "target.value = :target and obj.id = istarget.id.id and istarget.id.name = 'istarget' and "
                + "istarget.value = 0";

        queryParams.put("className", compactWikiSerializer.serialize(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        queryParams.put(TARGET, compactWikiSerializer.serialize(targetRef));

        try {
            Query query = queryManager.createQuery(workflowsQuery, Query.HQL);
            query.bindValues(queryParams);
            return (String) query.execute().get(0);
        } catch (QueryException e) {
            throw new RuntimeException(e);
        }
    }
}
