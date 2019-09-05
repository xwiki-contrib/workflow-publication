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
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatingEvent;
import org.xwiki.bridge.event.DocumentDeletingEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

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
 *     FIXME: should we move / rename the draft, too? We probably should.
 *   <li> if the event is a rename/move of a draft, we restore its original workflow state, and try to find the new location for the target.
 *    If this can be determined, and there is not already a document at the new target location, and the user doing the move has rights
 *    to publish the draft and it is in published state, we move the target instantly.
 *    Otherwise we note the new target location in a separate attribute of the workflow and perform the move when the document gets published.
 *    FIXME: not yet; instead we just do nothing except undoing the action performed when we thought it is a "Copy".
 * </ul>
 *
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

    /**
     * The current entity reference resolver, to resolve the notions class reference.
     */
    @Inject
    @Named("current/reference")
    protected DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;

    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;

    @Inject
    protected DocumentReferenceResolver<String> defaultDocRefResolver;

    @Inject
    private QueryManager queryManager;

    /**
     * The execution, to get the context from it.
     */
    @Inject
    protected Execution execution;

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

    public final static int DRAFT = 0;

    public final static int PUBLISHED = 1;

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_VALID = "valid";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    //
    // internal use: key names for data stored in the current context
    //

    private final static String TARGET_DOCUMENT_CREATED_REMARK = PublicationWorkflowRenameListener.class.getName() + ";targetcreated;";

    private final static String DRAFT_DOCUMENT_CREATED_REMARK = PublicationWorkflowRenameListener.class.getName() + ";draftcreated;";

    private final static String DOCREF_KEY = "doc";

    private final static String WORKFLOW_KEY = "wf";

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
        if (event instanceof DocumentCreatingEvent) {
            handleDocumentCreation((XWikiDocument) source, (XWikiContext) data);
        } else if (event instanceof DocumentDeletingEvent) {
            handleDocumentDeletion((XWikiDocument) source, (XWikiContext) data);
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
            String defaultMessage = "The target was moved / renamed. Set the new location to " + serializedNewTargetName + ".";
            String message =
                getMessage("workflow.move.updateDraft", defaultMessage,
                    Arrays.asList(serializedNewTargetName));
            context.getWiki().saveDocument(draftDocument, message, false, context);
        }
    }

    private void handleDraftDocumentCreation(XWikiDocument currentDocument, BaseObject workflowInstance,
        XWikiContext context) throws QueryException
    {
        // we first check if there is another draft document;
        // only if it is, assume a copy and delete the workflow
        // otherwise we are a XAR import, wiki creation or maybe create from template
        final String currenDocumentFullName = this.compactWikiSerializer.serialize(currentDocument.getDocumentReference());
        final String serializedTargetName = workflowInstance.getStringValue(WF_TARGET_FIELDNAME);
        List<String> results = getDraftOrTargetPagesForWorkflow(serializedTargetName, false);
        results.remove(currenDocumentFullName);
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

        String defaultSaveMessage = "Restore workflow object after rename of draft document with target " + serializedOldTargetName + '.';
        String saveMessage =
            getMessage("workflow.move.restoreWorkflowOnDraftAfterMove", defaultSaveMessage, null);
        context.getWiki().saveDocument(newDraftDocument, saveMessage, true, context);

        // now ... do we update the target or not?
        // currently we do not do it
    }


    /**
     * helper to look up either the draft or the target document.
     * in either case try to find the page(s) that have a workflow document which &quot;target&quot; attribute
     * points to given target name.
     * @param targetName the serialized target document name
     * @param isTarget if true, find target, otherwise find draft
     * @return a list of documentReferences (as strings); should usually contain at most one element
     * @throws QueryException
     */
    private List<String> getDraftOrTargetPagesForWorkflow(String targetName, boolean isTarget)
        throws QueryException
    {
        // Searching for the draft
        List<Object> queryParams = new ArrayList<Object>();
        String workflowsQuery =
            "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget where "
                + "obj.className = ? and obj.id = target.id.id and target.id.name = ? and target.value = ? and "
                + "obj.id = istarget.id.id and istarget.id.name = ? and istarget.value = ?";

        queryParams.add(compactWikiSerializer.serialize(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        queryParams.add(WF_TARGET_FIELDNAME);
        queryParams.add(targetName);
        queryParams.add(WF_IS_TARGET_FIELDNAME);
        queryParams.add(isTarget ? PUBLISHED : DRAFT);

        Query query = queryManager.createQuery(workflowsQuery, Query.HQL);
        query.bindValues(queryParams);
        List<String> results = query.execute();
        return results;
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
        // trim the message, whichever that is, to 255 characters, otherwise we're in trouble
        if (message.length() > 255) {
            // add some dots to show that it was trimmed
            message = message.substring(0, 252) + "...";
        }
        return message;
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
