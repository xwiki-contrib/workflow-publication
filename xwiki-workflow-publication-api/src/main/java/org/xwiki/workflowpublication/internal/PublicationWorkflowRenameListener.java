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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.bridge.event.DocumentDeletingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.localization.ContextualLocalizationManager;
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
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;

/**
 * Event listener to listen to document copy or renaming events and update the target of the draft according to the new location.
 * 
 * Unfortunately it is not possible to clearly distinguish between a copy and a move/rename for baseline of the supported XWiki platform
 * (that is 7.2). In that version there is no DocumentRenamingEvent or DocumentCopyEvent (introduced in 11.1), instead a
 * move/rename shows as a DocumentCreatingEvent followed by a corresponding DocumentDeletingEvent, while a copy is just a DocumentCreatingEvent.
 *
 * So if we get noticed by a DocumentCreatedEvent that a document popped into existence with a Workflow already attached to it,
 * we assume it is a copy. However we remember in the context that this document has been created with a certain targetDocument
 * so we can change our mind if another document with the same target gets deleted in the same context; in that case we undo
 * the action we did for the copy and handle it as a move.
 *
 * Currently the action taken for the events is:
 *
 * <ul>
 *   <li> if the event is a copy, we remove the workflow object from the copy, to avoid having two pages with the same target.
 *     We trust the person doing the copy to set up a new workflow if desired, instead of making guesses on our own.
 *   <li> if the event is a rename/move and the document is a target, we instead update the targetDocument attribute
 *     in the workflow object to point to itself, and also update the targetDocument of the corresponding draft.
 *     Depending on the configuration of the workflow, drafts can also be moved automatically (see below).
 *   <li> if the event is a rename/move of a draft, we restore its original workflow state.
 *    Otherwise we note the new target location in a separate attribute of the workflow and perform the move when the
 *    document gets published.
 *    Depending on the configuration of the workflow, the target can also be moved automatically (see below).
 * </ul>
 *
 * Depending on the configuration of the option WF_MOVE_STRATEGY_FIELDNAME in the workflow configuration, the
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
 *     <li>Verify that the equivalent document has not been moved already.</li>
 *     <li>Verify that the user performing the move has edit rights on the target space.</li>
 *     <li>Verify that no document already exists in the new location.</li>
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

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;

    @Inject
    protected DocumentReferenceResolver<String> defaultDocRefResolver;

    @Inject
    private QueryManager queryManager;

    /**
     * Reference string serializer for diagnostic messages, etc.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;

    /**
     * Reference string serializer to be used to store the workflow target.
     */
    @Inject
    @Named("compactwiki")
    protected EntityReferenceSerializer<String> compactWikiSerializer;

    /**
     * Used to check access rights when moving automatically equivalent documents.
     */
    @Inject
    private AuthorizationManager authorizationManager;

    /**
     * For translations.
     */
    @Inject
    private ContextualLocalizationManager localizationManager;

    public static final String WF_CONFIG_REF_FIELDNAME = "workflow";

    public static final String WF_TARGET_FIELDNAME = "target";

    public final static String WF_STATUS_FIELDNAME = "status";

    public final static String WF_STATUS_AUTHOR_FIELDNAME = "statusAuthor";

    public final static String WF_IS_TARGET_FIELDNAME = "istarget";

    public final static String WF_IS_DRAFTSPACE_FIELDNAME = "defaultDraftSpace";

    public static final String WF_MOVE_STRATEGY_FIELDNAME = "moveStrategy";

    public final static int DRAFT = 0;

    public final static int PUBLISHED = 1;

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_VALID = "valid";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    public static final String MOVE_STRATEGY_DO_NOTHING = "doNothing";

    public static final String MOVE_STRATEGY_MOVE_TARGET_IF_UNPUBLISHED = "moveTargetIfUnpublished";

    public static final String MOVE_STRATEGY_MOVE_TARGET = "moveTarget";

    public static final String MOVE_STRATEGY_MOVE_DRAFTS = "moveDrafts";

    public static final String MOVE_STRATEGY_MOVE_ALL = "moveAll";

    //
    // internal use: key names for data stored in the current context
    //

    private final static String TARGET_DOCUMENT_CREATED_REMARK = PublicationWorkflowRenameListener.class.getName() + ";targetcreated;";

    private final static String DRAFT_DOCUMENT_CREATED_REMARK = PublicationWorkflowRenameListener.class.getName() + ";draftcreated;";

    private final static String DOCREF_KEY = "doc";

    private final static String WORKFLOW_KEY = "wf";

    private final static String EQUIVALENT_DOCUMENT_MOVE_KEY = "publicationWorkflowEquivalentDocumentMove";

    @Inject
    protected PublicationWorkflow publicationWorkflow;

    @Inject
    protected PublicationRoles publicationRoles;

    /**
     * The events observed by this observation manager.
     */
    private final List<Event> eventsList = new ArrayList<Event>(Arrays.asList(new DocumentCreatingEvent(), new DocumentDeletingEvent()));

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
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiContext context = (XWikiContext) data;
        XWikiDocument document = (XWikiDocument) source;

        if (event instanceof DocumentCreatingEvent) {
            handleDocumentCreation(document, context);
        } else if (event instanceof DocumentDeletingEvent) {
            handleDocumentDeletion(document, context);
        }
    }

    private void handleDocumentCreation(XWikiDocument currentDocument, XWikiContext context)
    {
        try {
            // Check if the new document is a workflow document
            if (!publicationWorkflow.isWorkflowDocument(currentDocument, context)) {
                return;
            }

            DocumentReference currentDocumentReference = currentDocument.getDocumentReference();

            // Get the workflow object
            BaseObject workflowInstance =
                currentDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                    currentDocumentReference));

            // Check if the document is published and is a target
            if (StringUtils.equals(workflowInstance.getStringValue(WF_STATUS_FIELDNAME), STATUS_PUBLISHED)
                && workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME) == PUBLISHED) {
                // if we knew this is a move, we could handle it right away
                // but instead we must assume this is a copy
                handleTargetDocumentCreation(currentDocument, workflowInstance, context);
            } else
                // or if it is a draft
                if (workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME) == DRAFT) {
                    handleDraftDocumentCreation(currentDocument, workflowInstance, context);
            } else {
                // TODO: what else might it be? an archived target maybe?
                logger.warn("workflow document created at [{}] which is neither a draft or a published target; its workflow state is [{}]",
                    stringSerializer.serialize(currentDocumentReference), workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME));
            }

        } catch (XWikiException e) {
            logger.warn("Could not get workflow config document for document [{}]",
                stringSerializer.serialize(currentDocument.getDocumentReference()));
        } catch (QueryException e) {
            logger.warn("Could not get compagnion document (draft or target) for created document [{}]",
                stringSerializer.serialize(currentDocument.getDocumentReference()));
        }
    }

    private void handleDocumentDeletion(XWikiDocument doc, XWikiContext context)
    {
        final XWikiDocument document = doc.getOriginalDocument();
        final DocumentReference documentReference = document.getDocumentReference();
        final String documentReferenceString = stringSerializer.serialize(documentReference);
        try {
            // Check if the old document is a workflow document
            if (!publicationWorkflow.isWorkflowDocument(document, context)) {
                return;
            }

            logger.debug("document about to deleted is [{}]", documentReferenceString);

            // Get the workflow object
            BaseObject workflowInstance =
                document.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                    documentReference));

            // Check if the document is target and the the current user can validate it
            if (workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME) == PUBLISHED) {
                // XXX: here we rather want to check rights for the draft reference, maybe?
                if (publicationRoles.canValidate(context.getUserReference(), document, context)) {
                    handleTargetDocumentDelete(documentReference, workflowInstance, context);
                }
            } else {
                // or if it is not a target
                if (workflowInstance.getIntValue(WF_IS_TARGET_FIELDNAME) == DRAFT) {
                    handleDraftDocumentDelete(documentReference, workflowInstance, context);
                }
            }

        } catch (XWikiException e) {
            logger.warn("Could not get workflow config document for deleted document [{}]", documentReferenceString);
        } catch (QueryException e) {
            logger.warn("Could not get compagnion document (draft or target)for deleted document [{}]", documentReferenceString);
        }
    }

    private void handleTargetDocumentCreation(XWikiDocument targetDoc, BaseObject workflowInstance, XWikiContext context)
        throws XWikiException, QueryException
    {
        // we first check if there is another target document;
        // if it is, and we are not the "right" target, only then delete the workflow
        // otherwise we are in a first "publish" step, a XAR import or wiki creation
        final DocumentReference targetDocRef = targetDoc.getDocumentReference();
        final String targetDocFullName = this.compactWikiSerializer.serialize(targetDocRef);
        final String serializedTargetName = workflowInstance.getStringValue(WF_TARGET_FIELDNAME);
        if (targetDocFullName.equals(serializedTargetName)) {
            // we are the "right" target. skip even querying for other target documents
            return;
        }
        List<String> results = getDraftOrTargetPagesForWorkflow(serializedTargetName, true);
        results.remove(targetDocFullName);
        if (results.isEmpty()) {
            return;
        }

        logger.debug("remember workflow from [{}] in case we need it again", stringSerializer.serialize(targetDocRef) );
        rememberTargetDocumentCreated(targetDocRef, workflowInstance, context);
        targetDoc.removeXObject(workflowInstance);
    }

    private void handleTargetDocumentDelete(DocumentReference oldTargetDoc, BaseObject oldWorkflowInstance, XWikiContext context)
        throws QueryException, XWikiException
    {
        String serializedOldTargetName = oldWorkflowInstance.getStringValue(WF_TARGET_FIELDNAME);

        // see if we have a recently document created with the same workflow target
        // which should be updated to the new target (itself)
        Map<String, Object> backupData = hasTargetDocumentBeenCreated(serializedOldTargetName, context);

        if (backupData == null) {
            // it seem the target document really got deleted :(
            // what do we do now? tell the draft document about it?
            return;
        }
        DocumentReference newTargetDocumentReference = (DocumentReference) backupData.get(DOCREF_KEY);
        String serializedNewTargetName = compactWikiSerializer.serialize(newTargetDocumentReference, context.getWiki());
        BaseObject backupOfWorkflowInstance = (BaseObject)backupData.get(WORKFLOW_KEY);

        // ... as long as it actually exists
        final XWiki xwiki = context.getWiki();
        XWikiDocument newTargetDocument  = xwiki.getDocument(newTargetDocumentReference, context);
        if (newTargetDocument.isNew()) {
            logger.warn("could not find moved version [{}] of about to deleted target document [{}]", serializedNewTargetName,
                stringSerializer.serialize(oldTargetDoc));
            return;
        }

        // and the current user can validate it
        if (!publicationRoles.canValidate(context.getUserReference(), newTargetDocument, context)) {
            logger.warn("moved document at [{}] should have target updated, but the current user has insufficient rights to do this.",
                stringSerializer.serialize(newTargetDocumentReference));
            return;
        }

        newTargetDocument.setXObject(0, backupOfWorkflowInstance);
        BaseObject newWorkflowInstance = newTargetDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
            newTargetDocumentReference));

        // Set the current document as target
        newWorkflowInstance.setStringValue(WF_TARGET_FIELDNAME, serializedNewTargetName);

        // now we need to save the new document, as we are already in the deletion step of the other document
        String defaultMoveMessage = "Update the workflow target from "+serializedOldTargetName +" to " + serializedNewTargetName + ".";
        String moveMessage =
            getMessage("workflow.move.updateTarget", defaultMoveMessage, Arrays.asList(serializedOldTargetName, serializedNewTargetName));

        context.getWiki().saveDocument(newWorkflowInstance.getOwnerDocument(), moveMessage, true, context);

        // Searching for the draft
        List<String> results = getDraftOrTargetPagesForWorkflow(serializedOldTargetName, false);

        // Update the target value in the draft(s)
        // maybe we should check for permissions here, too?
        for (String result : results) {
            DocumentReference docRef = this.defaultDocRefResolver.resolve(result, context.getWikiReference());
            XWikiDocument draftDocument = context.getWiki().getDocument(docRef, context);

            // Get the workflow object
            BaseObject draftWorkflowInstance =
                draftDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                    newTargetDocumentReference));
            draftWorkflowInstance.setStringValue(WF_TARGET_FIELDNAME, serializedNewTargetName);

            // Save the draft document
            String defaultMessage =
                String.format("The target was moved / renamed. Set the new location to [%s].", serializedNewTargetName);
            String message = getMessage("workflow.move.updateDraft", defaultMessage,
                Arrays.asList(serializedNewTargetName));
            context.getWiki().saveDocument(draftDocument, message, false, context);

            String moveStrategy = getMoveStrategy(draftWorkflowInstance);
            if (moveStrategy != null
                && (moveStrategy.equals(MOVE_STRATEGY_MOVE_ALL) || moveStrategy.equals(MOVE_STRATEGY_MOVE_DRAFTS))
                && !(context.containsKey(EQUIVALENT_DOCUMENT_MOVE_KEY)
                     && context.get(EQUIVALENT_DOCUMENT_MOVE_KEY).equals(true))) {
                // Try to move the given draft to a new location in order to match the move performed on the target
                // document
                DocumentReference newDraftDocumentReference = computeNewEquivalentDocumentReference(oldTargetDoc,
                    newTargetDocumentReference, docRef, context);

                if (!newDraftDocumentReference.equals(docRef)) {
                    if (tryToMoveEquivalentDocument(oldTargetDoc,
                        newTargetDocumentReference, docRef, newDraftDocumentReference, context)) {
                        logger.info("The draft document [{}] has been moved to [{}]", docRef,
                            newDraftDocumentReference);
                    } else {
                        logger.warn("The draft document [{}] could not be moved", docRef);
                    }
                }
            }
        }
    }

    private void handleDraftDocumentCreation(XWikiDocument currentDocument, BaseObject workflowInstance,
        XWikiContext context) throws QueryException
    {
        // we first check if there is another draft document;
        // only if it is, assume a copy and delete the workflow
        // otherwise we are a XAR import, wiki creation or maybe create from template
        final String currentDocumentFullName = this.compactWikiSerializer.serialize(currentDocument.getDocumentReference());
        final String serializedTargetName = workflowInstance.getStringValue(WF_TARGET_FIELDNAME);
        List<String> results = getDraftOrTargetPagesForWorkflow(serializedTargetName, false);
        results.remove(currentDocumentFullName);
        if (results.isEmpty()) {
            return;
        }

        // as there is another document having the same target, we assume a copy
        // and remove the workflow object from this document.
        // we keep a copy of it in the current context in case the other document
        // is deleted with the same context
        logger.debug("remember workflow from draft [{}] in case we need it again", stringSerializer.serialize(currentDocument.getDocumentReference()) );
        rememberDraftDocumentCreated(currentDocument.getDocumentReference(), workflowInstance, context);
        currentDocument.removeXObject(workflowInstance);
    }

    private void handleDraftDocumentDelete(DocumentReference oldDraftDoc, BaseObject oldWorkflowInstance,
        XWikiContext context) throws QueryException, XWikiException
    {
        String serializedOldTargetName = oldWorkflowInstance.getStringValue(WF_TARGET_FIELDNAME);

        // look if we have a recently created draft
        Map<String, Object> backupData = hasDraftDocumentBeenCreated(serializedOldTargetName, context);

        if (backupData == null) {
            // it seem the draft document really got deleted :(
            // what do we do now? tell the target document about it?
            return;
        }
        DocumentReference newDraftDocumentReference = (DocumentReference) backupData.get(DOCREF_KEY);
        BaseObject backupOfWorkflowInstance = (BaseObject)backupData.get(WORKFLOW_KEY);

        // which should actually exist
        final XWiki xwiki = context.getWiki();
        XWikiDocument newDraftDocument = xwiki.getDocument(newDraftDocumentReference, context);
        if (newDraftDocument.isNew()) {
            logger.debug("could not find moved version [{}] of about to deleted draft document [{}]", stringSerializer.serialize(newDraftDocumentReference),
                stringSerializer.serialize(oldDraftDoc));
            return;
        }

        // then we set (restore) the workflow object for it
        logger.debug("restore workflow for moved draft [{}]", stringSerializer.serialize(newDraftDocumentReference) );
        newDraftDocument.setXObject(0, backupOfWorkflowInstance);
        backupOfWorkflowInstance = newDraftDocument.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
            newDraftDocumentReference));

        // TODO: Add option to silently update the moved / renamed document without creating a new version
        String defaultSaveMessage = "Restore workflow object after rename of draft document with target " + serializedOldTargetName + '.';
        String saveMessage =
            getMessage("workflow.move.restoreWorkflowOnDraftAfterMove", defaultSaveMessage, null);
        context.getWiki().saveDocument(newDraftDocument, saveMessage, true, context);

        String moveStrategy = getMoveStrategy(backupOfWorkflowInstance);

        if (moveStrategy != null
            && !moveStrategy.equals(MOVE_STRATEGY_DO_NOTHING)
            && !(context.containsKey(EQUIVALENT_DOCUMENT_MOVE_KEY)
                 && context.get(EQUIVALENT_DOCUMENT_MOVE_KEY).equals(true))) {
            DocumentReference oldTargetReference = defaultDocRefResolver.resolve(serializedOldTargetName);
            DocumentReference newTargetReference = computeNewEquivalentDocumentReference(oldDraftDoc,
                newDraftDocumentReference, oldTargetReference, context);

            // Verify that we have been able to compute the new target name
            if (!newTargetReference.equals(oldTargetReference)) {
                if (xwiki.exists(oldTargetReference, context)
                    && (moveStrategy.equals(MOVE_STRATEGY_MOVE_ALL) || moveStrategy.equals(MOVE_STRATEGY_MOVE_TARGET)))
                {
                    if (tryToMoveEquivalentDocument(oldDraftDoc, newDraftDocumentReference,
                        oldTargetReference, newTargetReference, context)) {
                        logger.info("The target document [{}] has been moved to [{}]", oldTargetReference,
                            newTargetReference);
                    } else {
                        logger.warn("The target document [{}] could not be moved", oldTargetReference);
                    }
                } else if (moveStrategy.equals(MOVE_STRATEGY_MOVE_TARGET_IF_UNPUBLISHED)) {
                    backupOfWorkflowInstance.set(WF_TARGET_FIELDNAME,
                        compactWikiSerializer.serialize(newTargetReference, context.getWiki()), context);

                    // TODO: Improve save message for this particular case
                    context.getWiki().saveDocument(newDraftDocument, saveMessage, true, context);
                }
            }
        }
    }

    /**
     * Based on the provided references, will try to compute the new equivalent document reference of the old
     * equivalent document reference.
     *
     * @param oldCurrentDocumentReference the old reference of the document that is currently being moved
     * @param newCurrentDocumentReference the new reference of the document that is currently being moved
     * @param oldEquivalentDocumentReference the new reference of the equivalent document
     * @param context the current context
     * @return the reference of the new equivalent document reference. If this reference could not be computed, the
     * old equivalent document reference is returned.
     */
    private DocumentReference computeNewEquivalentDocumentReference(DocumentReference oldCurrentDocumentReference,
        DocumentReference newCurrentDocumentReference, DocumentReference oldEquivalentDocumentReference,
        XWikiContext context)
    {
        // Compare the differences between the original draft and target references
        // One option would be to do a three way merge with :
        // * Previous : the old draft name
        // * Current : the old target name
        // * New : the new draft name
        // However this option does not work well with small strings, such as serialized document references.
        // Instead, we will be creating a diff based on the old draft reference and the new draft reference,
        // and then we'll try to apply this diff to the target reference. If the diff applies correctly,
        // and if it corresponds to a valid document name, we can continue. Else, we abort.

        // Start by splitting the different document references into an array of strings, which will be easier
        // to diff.
        List<String> oldCurrentSplittedReference = createSplitReference(oldCurrentDocumentReference);
        List<String> newCurrentSplittedReference = createSplitReference(newCurrentDocumentReference);
        List<String> oldEquivalentSplittedReference = createSplitReference(oldEquivalentDocumentReference);

        // Create the patch and try to apply it
        Patch<String> patch = DiffUtils.diff(oldCurrentSplittedReference, newCurrentSplittedReference);
        List<String> newEquivalentSplittedReference;
        try {
            newEquivalentSplittedReference = DiffUtils.patch(oldEquivalentSplittedReference, patch);
        } catch (PatchFailedException e) {
            logger.warn("Unable to compute new location for the document [{}]", oldEquivalentDocumentReference);
            logger.debug("Error when applying patch : [{}]", ExceptionUtils.getRootCause(e), e);
            return oldEquivalentDocumentReference;
        }

        // Reconstruct the new equivalent reference
        String newEquivalentName = newEquivalentSplittedReference.get(newEquivalentSplittedReference.size() - 1);
        newEquivalentSplittedReference.remove(newEquivalentSplittedReference.size() - 1);

        return new DocumentReference(context.getWikiId(), newEquivalentSplittedReference, newEquivalentName);
    }

    /**
     * Will try to move the equivalent document of the one provided in parameter to a new location.
     * This method applies when the draft or the target is moved somewhere else and we need to also need to move
     * the corresponding draft or target.
     *
     * @param oldCurrentDocumentReference the old reference of the document that is currently being moved
     * @param newCurrentDocumentReference the new reference of the document that is currently being moved
     * @param oldEquivalentDocumentReference the old reference of the equivalent document
     * @param newEquivalentDocumentReference the new reference of the equivalent document
     * @param context the current context
     * @return true if the move happened, false otherwise
     */
    private boolean tryToMoveEquivalentDocument(DocumentReference oldCurrentDocumentReference,
        DocumentReference newCurrentDocumentReference, DocumentReference oldEquivalentDocumentReference,
        DocumentReference newEquivalentDocumentReference, XWikiContext context)
    {
        // Conditions for moving / renaming the other document
        // * The other document should exist (maybe it has already been renamed)
        // * The other document should not overwrite another document present on its new document reference
        // * The user executing the rename should have edit rights on the new document reference of the equivalent
        // document
        XWiki xwiki = context.getWiki();

        // Check if the old equivalent document exists
        if (!xwiki.exists(oldEquivalentDocumentReference, context)) {
            logger.info("The equivalent document [{}] does not exist anymore", oldEquivalentDocumentReference);
            return false;
        }

        // Check if the new equivalent document exists
        if (xwiki.exists(newEquivalentDocumentReference, context)
            || newEquivalentDocumentReference.equals(newCurrentDocumentReference)) {
            logger.warn("Cannot move [{}] to destination [{}] as a document already exists on this location",
                oldEquivalentDocumentReference, newEquivalentDocumentReference);
            return false;
        }

        // Check if the user performing the move has edit rights on the new document reference
        if (!authorizationManager.hasAccess(Right.EDIT, context.getUserReference(), newEquivalentDocumentReference)) {
            logger.warn("Cannot move [{}] to destination [{}] as the current user [{}] has no edit rights on the "
                + "destination document", oldEquivalentDocumentReference, newEquivalentDocumentReference,
                context.getUserReference());
            return false;
        }

        try {
            // Before doing the rename of a document, we'll add a key in the context indicating that this move
            // was triggered by this event listener, so that we do not end up in a loop.
            context.put(EQUIVALENT_DOCUMENT_MOVE_KEY, true);
            logger.info("Moving equivalent document [{}] to destination [{}]", oldEquivalentDocumentReference,
                newEquivalentDocumentReference);
            xwiki.renameDocument(oldEquivalentDocumentReference, newEquivalentDocumentReference, true, null, null,
                context);
            context.remove(EQUIVALENT_DOCUMENT_MOVE_KEY);
        } catch (Exception e) {
            logger.error("Failed to move [{}] to destination [{}]", oldEquivalentDocumentReference,
                newEquivalentDocumentReference, e);
            return false;
        }

        return true;
    }

    /**
     * From the given document reference, create a list that contains the reversed reference chain of this document,
     * with the exclusion of the wiki reference.
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
     * helper to look up either the draft or the target document.
     * in either case try to find the page(s) that have a workflow document which &quot;target&quot; attribute
     * points to given target name.
     * @param targetName the serialized target document name
     * @param isTarget if true, find target, otherwise find draft
     * @return a list of documentReferences (as strings); should usually contain at most one element
     * @throws QueryException if an error occurred during the query execution
     */
    private List<String> getDraftOrTargetPagesForWorkflow(String targetName, boolean isTarget)
        throws QueryException
    {
        // Searching for the draft
        Map<String, Object> queryParams = new HashMap<>();
        String workflowsQuery =
            "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget "
                + "where obj.className = :className and obj.id = target.id.id and target.id.name = 'target' and "
                + "target.value = :target and obj.id = istarget.id.id and istarget.id.name = 'istarget' and "
                + "istarget.value = :istarget";

        queryParams.put("className", compactWikiSerializer.serialize(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        queryParams.put(WF_TARGET_FIELDNAME, targetName);
        queryParams.put(WF_IS_TARGET_FIELDNAME, isTarget ? PUBLISHED : DRAFT);

        Query query = queryManager.createQuery(workflowsQuery, Query.HQL);
        query.bindValues(queryParams);
        return query.execute();
    }

    /**
     * @param key Translation key
     * @param params Parameters to include in the translation
     * @param defaultMessage Message to display if the message tool finds no translation
     * @return message to use
     */
    protected String getMessage(String key, String defaultMessage, List<String> params)
    {
        String message = (params == null)
            ? localizationManager.getTranslationPlain(key)
            : localizationManager.getTranslationPlain(key, params.toArray());
        if (message == null || message.equals(key)) {
            message = defaultMessage;
        }
        // trim the message, whichever that is, to 1023 characters, otherwise we're in trouble
        if (message.length() > 1023) {
            // add some dots to show that it was trimmed
            message = message.substring(0, 1020) + "...";
        }
        return message;
    }

    /**
     * From the given {@link BaseObject}, returns the move strategy chosen as part of the workflow configuration.
     *
     * @param workflowObject the object to use for getting the configuration
     * @return the move strategy of the workflow, null if the workflow configuration could not be loaded
     */
    private String getMoveStrategy(BaseObject workflowObject)
    {
        try {
            List<String> result = queryManager.createQuery(
                "select moveStrategy.value from BaseObject configObj, StringProperty moveStrategy "
                    + "where configObj.name = :configFullName and configObj.className = "
                    + "'PublicationWorkflow.PublicationWorkflowConfigClass' and moveStrategy.id.name = 'moveStrategy' "
                    + "and configObj.id = moveStrategy.id.id",
                Query.HQL)
                .bindValue("configFullName", workflowObject.getStringValue(WF_CONFIG_REF_FIELDNAME))
                .setLimit(1).execute();

            if (result.size() == 1) {
                return result.get(0);
            } else {
                logger.error("Could not fetch the move strategy of the workflow object [{}]",
                    workflowObject.getDocumentReference());
                return null;
            }
        } catch (QueryException e) {
            logger.error("Failed to load the move strategy of the workflow object [{}]",
                workflowObject.getDocumentReference(), e);
            return null;
        }
    }

    //
    // helpers to remember previous events in the same context
    //

    private void rememberTargetDocumentCreated(DocumentReference targetDoc, BaseObject workflowInstance, XWikiContext context)
    {
        Map<String, Object> data = new HashMap<>();
        data.put(DOCREF_KEY, targetDoc);
        data.put(WORKFLOW_KEY, workflowInstance.duplicate());
        context.put(TARGET_DOCUMENT_CREATED_REMARK + workflowInstance.getStringValue(WF_TARGET_FIELDNAME), data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hasTargetDocumentBeenCreated(String serializedTargetName, XWikiContext context)
    {
        return (Map<String, Object>) context.get(TARGET_DOCUMENT_CREATED_REMARK + serializedTargetName);
    }

    private void rememberDraftDocumentCreated(DocumentReference draftDocRef, BaseObject workflowInstance,
        XWikiContext context)
    {
        Map<String, Object> data = new HashMap<>();
        data.put(DOCREF_KEY, draftDocRef);
        data.put(WORKFLOW_KEY, workflowInstance.duplicate());
        context.put(DRAFT_DOCUMENT_CREATED_REMARK + workflowInstance.getStringValue(WF_TARGET_FIELDNAME), data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hasDraftDocumentBeenCreated(String serializedTargetName, XWikiContext context)
    {
        return (Map<String, Object>) context.get(DRAFT_DOCUMENT_CREATED_REMARK + serializedTargetName);
    }
}
