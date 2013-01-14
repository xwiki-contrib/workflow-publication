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

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;
import org.xwiki.workflowpublication.WorkflowConfigManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.merge.MergeConfiguration;
import com.xpn.xwiki.doc.merge.MergeResult;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * @version $Id$
 */
@Component
public class DefaultPublicationWorkflow implements PublicationWorkflow
{
    public static final String WF_CONFIG_REF_FIELDNAME = "workflow";

    public static final String WF_TARGET_FIELDNAME = "target";

    public final static String WF_STATUS_FIELDNAME = "status";

    public final static String WF_IS_TARGET_FIELDNAME = "istarget";

    public final static int DRAFT = 0;

    public final static int PUBLISHED = 1;

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_VALID = "valid";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    public final static String STATUS_ARCHIVED = "archived";

    public static final EntityReference COMMENTS_CLASS = new EntityReference("XWikiComments", EntityType.DOCUMENT,
        new EntityReference("XWiki", EntityType.SPACE));

    /**
     * The reference to the xwiki rights, relative to the current wiki. <br />
     */
    public static final EntityReference RIGHTS_CLASS = new EntityReference("XWikiRights", EntityType.DOCUMENT,
        new EntityReference("XWiki", EntityType.SPACE));

    /**
     * The groups property of the rights class.
     */
    public static final String RIGHTS_GROUPS = "groups";

    /**
     * The levels property of the rights class.
     */
    public static final String RIGHTS_LEVELS = "levels";

    /**
     * The users property of the rights class.
     */
    public static final String RIGHTS_USERS = "users";

    /**
     * The 'allow / deny' property of the rights class.
     */
    public static final String RIGHTS_ALLOWDENY = "allow";

    /**
     * The execution, to get the context from it.
     */
    @Inject
    private Execution execution;

    /**
     * The current entity reference resolver, to resolve the notions class reference.
     */
    @Inject
    @Named("current")
    private DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;

    @Inject
    @Named("currentmixed")
    private DocumentReferenceResolver<String> currentMixedStringDocRefResolver;

    @Inject
    @Named("explicit")
    private DocumentReferenceResolver<String> explicitStringDocRefResolver;

    @Inject
    @Named("explicit")
    private DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;

    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> compactWikiSerializer;

    @Inject
    private WorkflowConfigManager configManager;

    @Inject
    private PublicationRoles publicationRoles;

    /**
     * Reference string serializer.
     */
    @Inject
    private EntityReferenceSerializer<String> stringSerializer;

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.workflowpublication.PublicationWorkflow#isWorkflowDocument(com.xpn.xwiki.doc.XWikiDocument,
     *      com.xpn.xwiki.XWikiContext)
     */
    @Override
    public boolean isWorkflowDocument(XWikiDocument document, XWikiContext context) throws XWikiException
    {
        BaseObject workflowInstance =
            document.getXObject(currentReferenceEntityResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        return workflowInstance != null;
    }

    @Override
    public DocumentReference getDraftDocument(DocumentReference targetRef, XWikiContext xcontext) throws XWikiException
    {
        String workflowsQuery =
            "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget where "
                + "obj.className = ? and obj.id = target.id.id and target.id.name = ? and target.value = ? and "
                + "obj.id = istarget.id.id and istarget.id.name = ? and istarget.value = 0";
        List<String> params =
            Arrays.asList(compactWikiSerializer.serialize(PUBLICATION_WORKFLOW_CLASS), WF_TARGET_FIELDNAME,
                compactWikiSerializer.serialize(targetRef), WF_IS_TARGET_FIELDNAME);
        List<String> results = xcontext.getWiki().getStore().search(workflowsQuery, 0, 0, params, xcontext);

        if (results.size() <= 0) {
            return null;
        }

        // if there are more results, use the first one
        return currentMixedStringDocRefResolver.resolve(results.get(0));
    }

    private DocumentReference createDraftDocument(DocumentReference targetRef, XWikiContext xcontext)
        throws XWikiException
    {
        if (getDraftDocument(targetRef, xcontext) != null) {
            return null;
        }

        XWikiDocument targetDocument = xcontext.getWiki().getDocument(targetRef, xcontext);
        // TODO: implement me
        return null;

    }

    @Override
    public boolean startWorkflow(DocumentReference docName, String workflowConfig, DocumentReference target,
        XWikiContext xcontext) throws XWikiException
    {
        XWikiDocument doc = xcontext.getWiki().getDocument(docName, xcontext);

        // Check that the target is free. i.e. no other workflow document targets this target
        if (this.getDraftDocument(target, xcontext) != null) {
            // TODO: put this error on the context
            return false;
        }

        BaseObject workflowObject =
            doc.newXObject(
                explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS, docName),
                xcontext);
        BaseObject wfConfig = configManager.getWorkflowConfig(workflowConfig, xcontext);
        if (wfConfig == null) {
            // TODO: put error on the context
            return false;
        }

        workflowObject.set(WF_CONFIG_REF_FIELDNAME, workflowConfig, xcontext);
        workflowObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(target, docName), xcontext);

        makeDocumentDraft(doc, workflowObject, xcontext);

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc,
            "Started workflow " + workflowConfig + " on document " + stringSerializer.serialize(docName), true,
            xcontext);

        return true;
    }

    @Override
    public List<String> startMatchingWorkflows(DocumentReference doc, DocumentReference target, XWikiContext xcontext)
    {
        // TODO: implement me
        return null;
    }

    @Override
    public boolean submitForModeration(DocumentReference document) throws XWikiException
    {

        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_DRAFT), DRAFT, xcontext);
        if (workflow == null) {
            return false;
        }

        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);

        String moderators = publicationRoles.getModerators(wfConfig, xcontext);
        // if there are no moderators, submit the doc for validation instead of moderation
        if (StringUtils.isEmpty(moderators)) {
            return this.submitForValidation(document);
        }

        // put the status to moderating
        workflow.set(WF_STATUS_FIELDNAME, STATUS_MODERATING, xcontext);
        // and put the rights
        String validators = publicationRoles.getValidators(wfConfig, xcontext);
        String contributors = publicationRoles.getContributors(wfConfig, xcontext);

        // give the view and edit right to moderators and validators ...
        setRights(doc, Arrays.asList("edit", "view"), Arrays.asList(moderators, validators), Arrays.<String> asList(),
            true, xcontext);
        // ... and only view for contributors
        addRights(doc, Arrays.asList("view"), Arrays.asList(contributors), Arrays.<String> asList(), true, xcontext);

        // save the doc.
        // TODO: prevent the save protection from being executed, when it would be implemented

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc,
            "Submitted document " + stringSerializer.serialize(document) + " to moderation ", true, xcontext);

        return true;
    }

    @Override
    public boolean refuseModeration(DocumentReference document, String reason) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_MODERATING), 0, xcontext);
        if (workflow == null) {
            return false;
        }

        // preconditions met, make the document back draft
        makeDocumentDraft(doc, workflow, xcontext);

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc, "Refused moderation: " + reason, false, xcontext);

        return true;
    }

    @Override
    public boolean submitForValidation(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_MODERATING, STATUS_DRAFT), DRAFT, xcontext);
        if (workflow == null) {
            return false;
        }

        // put the status to validating
        workflow.set(WF_STATUS_FIELDNAME, STATUS_VALIDATING, xcontext);
        // and put the rights
        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
        String validators = publicationRoles.getValidators(wfConfig, xcontext);
        String contributors = publicationRoles.getContributors(wfConfig, xcontext);
        String moderators = publicationRoles.getModerators(wfConfig, xcontext);

        // give the view and edit right to validators ...
        setRights(doc, Arrays.asList("edit", "view"), Arrays.asList(validators), Arrays.<String> asList(), true,
            xcontext);
        // ... and only view for contributors and moderators
        addRights(doc, Arrays.asList("view"), Arrays.asList(moderators, contributors), Arrays.<String> asList(), true,
            xcontext);

        // save the doc.
        // TODO: prevent the save protection from being executed.

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc,
            "Submitted document " + stringSerializer.serialize(document) + " to validation ", true, xcontext);

        return true;
    }

    @Override
    public boolean refuseValidation(DocumentReference document, String reason) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_VALIDATING), 0, xcontext);
        if (workflow == null) {
            return false;
        }

        // preconditions met, make the document back draft
        makeDocumentDraft(doc, workflow, xcontext);

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc, "Refused validation: " + reason, false, xcontext);

        return true;
    }

    @Override
    public boolean validate(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_VALIDATING), DRAFT, xcontext);
        if (workflow == null) {
            return false;
        }

        // put the status to valid
        workflow.set(WF_STATUS_FIELDNAME, STATUS_VALID, xcontext);
        // rights stay the same, only validator has the right to edit the document in the valid state, all other
        // participants to workflow can view it.

        // save the document prepared like this
        xcontext.getWiki().saveDocument(doc, "Marked document " + stringSerializer.serialize(document) + " as valid. ",
            true, xcontext);

        return true;
    }

    @Override
    public DocumentReference publish(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        // we can only publish from validating state, check that
        BaseObject workflow = validateWorkflow(doc, Arrays.asList(STATUS_VALIDATING, STATUS_VALID), DRAFT, xcontext);
        if (workflow == null) {
            return null;
        }

        String target = workflow.getStringValue(WF_TARGET_FIELDNAME);
        if (StringUtils.isEmpty(target)) {
            return null;
        }
        DocumentReference targetRef = explicitStringDocRefResolver.resolve(target, document);
        XWikiDocument newDocument = xcontext.getWiki().getDocument(targetRef, xcontext);

        // TODO: handle checking if the target document is free...

        this.copyContentsToNewVersion(doc, newDocument, xcontext);

        // setup the workflow and target flag, if a workflow doesn't exist already
        BaseObject newWorkflow = newDocument.getXObject(PUBLICATION_WORKFLOW_CLASS);
        if (newWorkflow == null) {
            newWorkflow = newDocument.newXObject(PUBLICATION_WORKFLOW_CLASS, xcontext);
            newWorkflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
            newWorkflow.set(WF_IS_TARGET_FIELDNAME, 1, xcontext);
            newWorkflow.set(WF_TARGET_FIELDNAME, target, xcontext);
            newWorkflow.set(WF_CONFIG_REF_FIELDNAME, workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
        }

        // TODO: figure out who should be the author of the published document
        // save the published document prepared like this
        xcontext.getWiki().saveDocument(newDocument, "Published new version of the document.", false, xcontext);

        // prepare the draft document as well
        // set the status
        workflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
        // give back the rights to the contributors, or do we? TODO: find out!
        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
        if (wfConfig != null) {
            String contributors = publicationRoles.getContributors(wfConfig, xcontext);
            String moderators = publicationRoles.getModerators(wfConfig, xcontext);
            String validators = publicationRoles.getValidators(wfConfig, xcontext);

            // give the view and edit right to contributors, moderators and validators
            setRights(doc, Arrays.asList("edit", "view"), Arrays.asList(contributors, moderators, validators),
                Arrays.<String> asList(), true, xcontext);
        }

        // save the the draft document prepared like this
        xcontext.getWiki().saveDocument(doc, "Published this document to " + stringSerializer.serialize(targetRef),
            false, xcontext);

        return targetRef;
    }

    @Override
    public DocumentReference unpublish(DocumentReference document, boolean forceToDraft) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument targetDoc = xcontext.getWiki().getDocument(document, xcontext);

        // check that the document to unpublish is a workflow published document
        BaseObject targetWorkflow =
            validateWorkflow(targetDoc, Arrays.asList(STATUS_PUBLISHED, STATUS_ARCHIVED), PUBLISHED, xcontext);
        if (targetWorkflow == null) {
            return null;
        }

        // get the draft ref
        DocumentReference draftDocRef = this.getDraftDocument(document, xcontext);
        if (draftDocRef != null) {
            // if there is a draft reference, check whether we need to overwrite it with the published version or not
            XWikiDocument draftDoc = xcontext.getWiki().getDocument(draftDocRef, xcontext);
            BaseObject workflow = draftDoc.getXObject(PUBLICATION_WORKFLOW_CLASS);
            String draftStatus = workflow.getStringValue(WF_STATUS_FIELDNAME);
            if (STATUS_PUBLISHED.equals(draftStatus) || !forceToDraft) {
                // a draft exists and it's either in state published, which means identical as the published doc, or
                // some draft and the overwriting of draft is not required
                // do nothing, draft will stay in place and target will be deleted at the end of this function
            } else {
                // the existing draft is not published and force to draft is required
                // copy the contents from target to draft
                this.copyContentsToNewVersion(targetDoc, draftDoc, xcontext);
                // make the draft doc draft again
                makeDocumentDraft(draftDoc, null, xcontext);
                // save the draft document
                xcontext.getWiki().saveDocument(draftDoc,
                    "Created draft from published document " + stringSerializer.serialize(document), true, xcontext);
            }
        } else {
            draftDocRef = this.createDraftDocument(document, xcontext);
        }

        if (draftDocRef != null) {
            // if draft creation worked fine, delete the published doc
            xcontext.getWiki().deleteDocument(targetDoc, xcontext);
            return draftDocRef;
        } else {
            // TODO: put exception on the context
            return null;
        }
    }

    @Override
    public boolean archive(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument publishedDoc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject publishedWorkflow =
            validateWorkflow(publishedDoc, Arrays.asList(STATUS_PUBLISHED), PUBLISHED, xcontext);
        if (publishedWorkflow == null) {
            return false;
        }

        // finally, preconditions are met, put the document on hidden (hoping that this is what archive actually means)
        // TODO: figure out what archive actually means
        publishedWorkflow.set(WF_STATUS_FIELDNAME, STATUS_ARCHIVED, xcontext);
        publishedDoc.setHidden(true);

        // save it
        xcontext.getWiki().saveDocument(publishedDoc, "Archived document", true, xcontext);

        return true;
    }

    @Override
    public DocumentReference unarchive(DocumentReference document, boolean forceToDraft) throws XWikiException
    {
        return this.unpublish(document, forceToDraft);
    }

    @Override
    public boolean publishFromArchive(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument archivedDoc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject archivedWorkflow =
            validateWorkflow(archivedDoc, Arrays.asList(STATUS_ARCHIVED), PUBLISHED, xcontext);
        if (archivedWorkflow == null) {
            return false;
        }

        // finally, preconditions are met, put the document on visible (hoping that this is what archive actually means)
        // TODO: figure out what archive actually means
        archivedWorkflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
        archivedDoc.setHidden(false);

        // save it
        xcontext.getWiki().saveDocument(archivedDoc, "Published document from archive", true, xcontext);

        return true;
    }

    /**
     * Function that marshalls the contents from ##fromDocument## to ##toDocument##, besides the workflow object, the
     * comment objects, the annotation objects, the rigths and the history. This function does not save the destination
     * document, the caller is responsible of that, so that they can perform additional operations on the destination
     * document before save.
     * 
     * @param fromDocument
     * @param toDocument
     * @return TODO
     * @throws XWikiException
     */
    private boolean copyContentsToNewVersion(XWikiDocument fromDocument, XWikiDocument toDocument, XWikiContext xcontext)
        throws XWikiException
    {
        // TODO: do this for all languages of the document to copy from

        // use a fake 3 way merge: previous is toDocument without comments, rights and wf object
        // current version is current toDocument
        // next version is fromDocument without comments, rights and wf object
        XWikiDocument previousDoc = toDocument.clone();
        previousDoc.removeXObjects(explicitReferenceDocRefResolver.resolve(COMMENTS_CLASS,
            previousDoc.getDocumentReference()));
        removeRights(previousDoc, xcontext);
        previousDoc.removeXObjects(explicitReferenceDocRefResolver.resolve(PUBLICATION_WORKFLOW_CLASS,
            previousDoc.getDocumentReference()));
        // set reference and language

        XWikiDocument nextDoc = fromDocument.clone();
        // I shouldn't do this, but for merge to work I need to have same doc ref and I don't know how to set it
        // otherwise
        nextDoc.setDocumentReference(toDocument.getDocumentReference());
        nextDoc.removeXObjects(explicitReferenceDocRefResolver.resolve(COMMENTS_CLASS, nextDoc.getDocumentReference()));
        nextDoc.setHidden(false);
        removeRights(nextDoc, xcontext);
        nextDoc.removeXObjects(explicitReferenceDocRefResolver.resolve(PUBLICATION_WORKFLOW_CLASS,
            nextDoc.getDocumentReference()));

        MergeResult result = toDocument.merge(previousDoc, nextDoc, new MergeConfiguration(), xcontext);

        List<LogEvent> exception = result.getLog().getLogs(LogLevel.ERROR);
        if (exception.isEmpty()) {
            return true;
        } else {
            StringBuffer exceptions = new StringBuffer();
            for (LogEvent e : exception) {
                if (exceptions.length() == 0) {
                    exceptions.append(";");
                }
                exceptions.append(e.getMessage());
            }
            throw new XWikiException(XWikiException.MODULE_XWIKI_DOC, XWikiException.ERROR_XWIKI_UNKNOWN,
                "Could not copy document contents from "
                    + stringSerializer.serialize(fromDocument.getDocumentReference()) + " to document "
                    + stringSerializer.serialize(toDocument.getDocumentReference()) + ". Caused by: "
                    + exceptions.toString());
        }
    }

    /**
     * Turns a document in a draft document by setting the appropriate rights, hidden, settings in the workflow object.
     * 
     * @param doc
     * @param workflow
     * @param xcontext
     * @throws XWikiException
     */
    private void makeDocumentDraft(XWikiDocument doc, BaseObject workflow, XWikiContext xcontext) throws XWikiException
    {
        BaseObject workflowObj = workflow;
        if (workflowObj == null) {
            workflowObj = doc.getXObject(PUBLICATION_WORKFLOW_CLASS);
        }

        workflow.set(WF_STATUS_FIELDNAME, STATUS_DRAFT, xcontext);
        workflow.set(WF_IS_TARGET_FIELDNAME, 0, xcontext);
        doc.setHidden(true);

        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);

        if (wfConfig != null) {
            String contributors = publicationRoles.getContributors(wfConfig, xcontext);
            String moderators = publicationRoles.getModerators(wfConfig, xcontext);
            String validators = publicationRoles.getValidators(wfConfig, xcontext);

            // give the view and edit right to contributors, moderators and validators
            setRights(doc, Arrays.asList("edit", "view"), Arrays.asList(contributors, moderators, validators),
                Arrays.<String> asList(), true, xcontext);
        }
    }

    private BaseObject validateWorkflow(XWikiDocument document, List<String> expectedStatuses,
        Integer expectedIsTarget, XWikiContext xcontext) throws XWikiException
    {
        if (!this.isWorkflowDocument(document, xcontext)) {
            // TODO: put error on the context
            return null;
        }
        BaseObject workflowObj = document.getXObject(PUBLICATION_WORKFLOW_CLASS);
        // check statuses
        if (!expectedStatuses.contains(workflowObj.getStringValue(WF_STATUS_FIELDNAME))) {
            // TODO: put error on the context
            return null;
        }
        // check is target (i.e. is published)
        int isTargetValue = workflowObj.getIntValue(WF_IS_TARGET_FIELDNAME, 0);
        if (!((expectedIsTarget > 0 && isTargetValue > 0) || (expectedIsTarget <= 0 && expectedIsTarget <= 0))) {
            // TODO: put error on the context
            return null;
        }
        return workflowObj;
    }

    private void setRights(XWikiDocument document, List<String> levels, List<String> groups, List<String> users,
        boolean allowdeny, XWikiContext context) throws XWikiException
    {
        // delete existing rights, if any
        this.removeRights(document, context);

        addRights(document, levels, groups, users, allowdeny, context);
    }

    private void addRights(XWikiDocument document, List<String> levels, List<String> groups, List<String> users,
        boolean allowdeny, XWikiContext context) throws XWikiException
    {
        // create a new object of type xwiki rights
        BaseObject rightsObject = document.newXObject(RIGHTS_CLASS, context);
        // put the rights and create
        rightsObject.set(RIGHTS_ALLOWDENY, allowdeny ? 1 : 0, context);
        // prepare the value for the groups property: it's a bit uneasy, we cannot pass a list to the BaseObject.set
        // and
        // to build the string we either need to know the separator, or we need to do this bad workaround to make
        // GroupsClass build the property value
        PropertyClass groupsPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_GROUPS);
        BaseProperty groupsProperty = groupsPropClass.fromStringArray((String[]) groups.toArray());
        rightsObject.set(RIGHTS_GROUPS, groupsProperty.getValue(), context);
        PropertyClass usersPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_USERS);
        BaseProperty usersProperty = usersPropClass.fromStringArray((String[]) users.toArray());
        rightsObject.set(RIGHTS_USERS, usersProperty.getValue(), context);
        PropertyClass levelsPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_LEVELS);
        BaseProperty levelsProperty = levelsPropClass.fromStringArray((String[]) levels.toArray());
        rightsObject.set(RIGHTS_LEVELS, levelsProperty.getValue(), context);
    }

    private void removeRights(XWikiDocument document, XWikiContext context) throws XWikiException
    {
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(RIGHTS_CLASS, document.getDocumentReference()));
    }

    /**
     * @return the xwiki context from the execution context
     */
    private XWikiContext getXContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }
}
