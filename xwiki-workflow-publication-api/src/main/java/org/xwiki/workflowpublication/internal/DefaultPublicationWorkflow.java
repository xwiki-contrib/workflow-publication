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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.suigeneris.jrcs.diff.DifferentiationFailedException;
import org.suigeneris.jrcs.diff.delta.Delta;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;
import org.xwiki.workflowpublication.WorkflowConfigManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.MetaDataDiff;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.doc.merge.MergeConfiguration;
import com.xpn.xwiki.doc.merge.MergeResult;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.ObjectDiff;
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

    public final static String STATUS_ARCHIVED = "archived";

    public final static String CONTEXTKEY_PUBLISHING = "publicationworkflow:publish";

    public static final EntityReference COMMENTS_CLASS = new EntityReference("XWikiComments", EntityType.DOCUMENT,
        new EntityReference("XWiki", EntityType.SPACE));

    /**
     * The reference to average ratings class used in the Ratings application
     */
    public static final EntityReference AVERAGE_RATINGS_CLASS =
        new EntityReference("AverageRatingsClass", EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));
    
    /**
     * The reference to average ratings class used in the Ratings application
     */
    public static final EntityReference RATINGS_CLASS =
        new EntityReference("RatingsClass", EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));

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
     * For translations.
     */
    @Inject
    private ContextualLocalizationManager localizationManager;

    /**
     * The execution, to get the context from it.
     */
    @Inject
    protected Execution execution;

    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<String> explicitStringDocRefResolver;

    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;

    @Inject
    @Named("compactwiki")
    protected EntityReferenceSerializer<String> compactWikiSerializer;

    @Inject
    protected WorkflowConfigManager configManager;

    @Inject
    protected PublicationRoles publicationRoles;

    /**
     * Reference string serializer.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;

    /**
     * Logging tool.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPublicationWorkflow.class);

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
            document.getXObject(explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                document.getDocumentReference()));
        return workflowInstance != null;
    }

    @Override
    public boolean isModified(XWikiDocument fromDoc, XWikiDocument toDoc, XWikiContext xcontext) throws XWikiException
    {
        List<Locale> fromLocales = fromDoc.getTranslationLocales(xcontext);
        List<Locale> toLocales = toDoc.getTranslationLocales(xcontext);

        // compare locales first (need to ignore order of locales)
        if (! new HashSet<Locale>(fromLocales).equals( new HashSet<Locale>(toLocales))) {
            LOGGER.debug("different locales for {} and {} : {} not equal to {}", fromDoc, toDoc, fromLocales, toLocales);
            return true;
        }
        if (!fromDoc.getDefaultLocale().equals(toDoc.getDefaultLocale())) {
            LOGGER.debug("different locales for {} and {} : {} not equal to {}", fromDoc, toDoc, fromDoc.getDefaultLocale(), toDoc.getDefaultLocale());
        }

        fromLocales.add(0, fromDoc.getDefaultLocale());
        for (Locale locale : fromLocales) {
            // do the same check for every locale; that wastes a bit of performance
            // as e.g. objects and attachments only need to be checked for the default locale.
            if (isModifiedSingleLanguage(fromDoc.getTranslatedDocument(locale, xcontext),
                                         toDoc.getTranslatedDocument(locale, xcontext), xcontext)) {
                LOGGER.debug("different versions for {} and {} in locale {}", fromDoc, toDoc, locale);
                return true;
            }
        }
        return false;
    }

    private boolean isModifiedSingleLanguage(XWikiDocument fromDoc, XWikiDocument toDoc, XWikiContext xcontext) throws XWikiException
    {
        // check if fromDoc is different from toDoc, using the same strategy we use in copyContentsToNewVersion: compare
        // document content, document metadata (besides author), compare objects besides comments, rights and
        // publication workflow class, compare attachments (including attachment content).
        XWikiDocument previousDoc = toDoc.clone();
        this.cleanUpIrrelevantDataFromDoc(previousDoc, xcontext);
        // set reference and language

        XWikiDocument nextDoc = fromDoc.duplicate(toDoc.getDocumentReference());
        this.cleanUpIrrelevantDataFromDoc(nextDoc, xcontext);
        // 0. content diff
        try {
            List<Delta> contentDiffs = previousDoc.getContentDiff(previousDoc, nextDoc, xcontext);
            if (contentDiffs.size() > 0) {
                LOGGER.debug("different content for {} and {}", previousDoc, nextDoc);
                // we found content differences, we stop here and return
                return true;
            }
        } catch (DifferentiationFailedException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_DIFF, XWikiException.ERROR_XWIKI_DIFF_CONTENT_ERROR,
                "Cannot make diff between content of documents "
                    + stringSerializer.serialize(fromDoc.getDocumentReference()) + " and documents "
                    + stringSerializer.serialize(toDoc.getDocumentReference()), e);
        }
        // 1. meta data diffs, other than document author
        List<MetaDataDiff> metaDiffs = previousDoc.getMetaDataDiff(previousDoc, nextDoc, xcontext);
        // if there is a change other than author, it's a real change
        for (MetaDataDiff metaDataDiff : metaDiffs) {
            if (!metaDataDiff.getField().equals("author") && !metaDataDiff.getField().equals("hidden")) {
                // is modified, return here, don't need to check the rest, we don't care
                LOGGER.debug("different meta for {} and {}: {}", previousDoc, nextDoc, metaDataDiff);
                return true;
            }
        }
        // 2. object diffs
        List<List<ObjectDiff>> objectDiffs = previousDoc.getObjectDiff(previousDoc, nextDoc, xcontext);
        if (objectDiffs.size() > 0) {
            LOGGER.debug("different objects for {} and {}", previousDoc, nextDoc);
            // is modified, return here, don't need to check the rest, we don't care
            return true;
        }
        // 3. attachment diffs
        // compare the attachments from the previous document to nextDocument, if there is one which is in one and not
        // in the other, scream change
        for (XWikiAttachment fromAttachment : previousDoc.getAttachmentList()) {
            // check if the attachment exists in the other document
            XWikiAttachment toAttachment = nextDoc.getAttachment(fromAttachment.getFilename());
            if (toAttachment == null) {
                // attachment does not exist in the new document, it's a change, return and stop
                return true;
            }
        }
        // check also the attachments in the nextDoc. If there is one which is not in previous doc, we scream
        // modification
        for (XWikiAttachment toAttachment : nextDoc.getAttachmentList()) {
            // check if the attachment exists in the other document
            XWikiAttachment fromAttachment = nextDoc.getAttachment(toAttachment.getFilename());
            if (fromAttachment == null) {
                // attachment does not exist in the old document, it's a change, return and stop
                return true;
            }
        }
        // for all common attachments, check their content and if we find 2 attachments with different content, scream
        // change
        for (XWikiAttachment fromAttachment : previousDoc.getAttachmentList()) {
            XWikiAttachment toAttachment = nextDoc.getAttachment(fromAttachment.getFilename());
            if (toAttachment == null) {
                // this should not happen because of the code above checks this - anyway, bail out is not case
                return true;
            }

            // quick exit: if file sizes differ, we do not need to check contents to be sure they differ
            if (fromAttachment.getFilesize() != toAttachment.getFilesize()) {
                return true;
            }
            LOGGER.debug("compare {} with {}", fromAttachment.getFilename(), toAttachment.getFilename());
            // compare the contents of the attachment to know if we should update it or not
            // TODO: figure out how could we do this without using so much memory
            boolean isSameAttachmentContent = false;
            try {
                InputStream fromInputStream = fromAttachment.getContentInputStream(xcontext);
                InputStream toInputStream = toAttachment.getContentInputStream(xcontext);
                try {
                    isSameAttachmentContent = IOUtils.contentEquals(fromInputStream, toInputStream);
                } finally {
                    IOUtils.closeQuietly(fromInputStream);
                    IOUtils.closeQuietly(toInputStream);
                }
            } catch (IOException ioe) {
                LOGGER.warn("could not load attachment " + fromAttachment.getFilename() + "; assume they differ in contents", ioe);
            }
            // unload the content of the attachments after comparison, since we don't need it anymore and we don't
            // want to waste memory
            toAttachment.setAttachment_content(null);
            fromAttachment.setAttachment_content(null);
            if (!isSameAttachmentContent) {
                // there is a change, return
                return true;
            }
        }

        // if nothing has happened previously, there is no change
        return false;
    }

    @Override
    public DocumentReference getDraftDocument(DocumentReference targetRef, XWikiContext xcontext) throws XWikiException
    {
        return this.getDraftDocument(targetRef, targetRef.getWikiReference().getName(), xcontext);
    }

    @Override
    public DocumentReference getDraftDocument(DocumentReference targetRef, String wiki, XWikiContext xcontext)
        throws XWikiException
    {
        String workflowsQuery =
            "select obj.name from BaseObject obj, StringProperty target, IntegerProperty istarget where "
                + "obj.className = ? and obj.id = target.id.id and target.id.name = ? and target.value = ? and "
                + "obj.id = istarget.id.id and istarget.id.name = ? and istarget.value = 0";
        // serialize the target WRT the passed wiki parameter
        String serializedTargetName = compactWikiSerializer.serialize(targetRef, new WikiReference(wiki));
        // the class needs to be serialized compact anyway, and it's a wikiless entity reference, so we don't need to
        // worry about on which wiki it gets serialized
        List<String> params =
            Arrays.asList(compactWikiSerializer.serialize(PUBLICATION_WORKFLOW_CLASS), WF_TARGET_FIELDNAME,
                serializedTargetName, WF_IS_TARGET_FIELDNAME);
        List<String> results = null;
        // query on the passed database
        String originalDatabase = xcontext.getDatabase();
        try {
            xcontext.setDatabase(wiki);
            results = xcontext.getWiki().getStore().search(workflowsQuery, 0, 0, params, xcontext);
        } finally {
            xcontext.setDatabase(originalDatabase);
        }

        if (results == null || results.size() <= 0) {
            return null;
        }

        // if there are more results, use the first one, resolve it relative to passed wiki reference
        return explicitStringDocRefResolver.resolve(results.get(0), new WikiReference(wiki));
    }

    @Override
    public DocumentReference createDraftDocument(DocumentReference targetRef, XWikiContext xcontext)
        throws XWikiException
    {
        if (getDraftDocument(targetRef, xcontext) != null) {
            return null;
        }

        XWikiDocument targetDocument = xcontext.getWiki().getDocument(targetRef, xcontext);

        // we can only create a draft for a published document, from the published or archived state.
        BaseObject workflow =
            validateWorkflow(targetDocument, Arrays.asList(STATUS_PUBLISHED, STATUS_ARCHIVED), PUBLISHED, xcontext);
        if (workflow == null) {
            return null;
        }

        return this.createDraftDocument(targetDocument, xcontext);
    }

    private DocumentReference createDraftDocument(XWikiDocument targetDocument, XWikiContext xcontext)
        throws XWikiException
    {
        DocumentReference targetRef = targetDocument.getDocumentReference();

        // if this document is not a workflow document, return nothing
        if (!this.isWorkflowDocument(targetDocument, xcontext)) {
            // TODO: put exception on the context
            return null;
        }
        // get the workflow config in the target document to get the default drafts space
        BaseObject wfConfig = configManager.getWorkflowConfigForWorkflowDoc(targetDocument, xcontext);
        if (wfConfig == null) {
            // TODO: put error on the context
            return null;
        }
        String defaultDraftsSpace = wfConfig.getStringValue(WF_IS_DRAFTSPACE_FIELDNAME);
        if (StringUtils.isEmpty(defaultDraftsSpace)) {
            // TODO: put exception on the context
            return null;
        }
        defaultDraftsSpace = defaultDraftsSpace.trim();
        // get a new document in the drafts space, starting with the name of the target document
        String draftDocName = xcontext.getWiki().getUniquePageName(defaultDraftsSpace, targetRef.getName(), xcontext);
        DocumentReference draftDocRef =
            new DocumentReference(targetRef.getWikiReference().getName(), defaultDraftsSpace, draftDocName);
        XWikiDocument draftDoc = xcontext.getWiki().getDocument(draftDocRef, xcontext);

        final Locale origLocale = xcontext.getLocale();
        XWikiDocument translatedDraftDoc;
        List<Locale> locales = targetDocument.getTranslationLocales(xcontext);
        locales.add(0, targetDocument.getDefaultLocale());

        for (Locale locale : locales) {
            translatedDraftDoc = copyTranslatedDocument(targetDocument, draftDoc, locale, xcontext);

            // workflow object: only needs to be set up for default locale
            if (locale.equals(targetDocument.getDefaultLocale())) {
                BaseObject draftWfObject =
                        draftDoc.newXObject(
                                explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS, draftDocRef),
                                xcontext);
                draftWfObject.set(WF_CONFIG_REF_FIELDNAME,
                        compactWikiSerializer.serialize(wfConfig.getDocumentReference(), draftDocRef), xcontext);
                draftWfObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(targetRef, draftDocRef), xcontext);
                this.makeDocumentDraft(draftDoc, draftWfObject, xcontext);
            }

            // setup the creator to the current user
            translatedDraftDoc.setCreatorReference(xcontext.getUserReference());
            // and save the document
            String defaultMessage = "Created draft for " + stringSerializer.serialize(targetRef) + ".";

            try {
                xcontext.setLocale(translatedDraftDoc.getRealLocale());
                String message =
                    getMessage("workflow.save.createDraft", defaultMessage,
                            Arrays.asList(stringSerializer.serialize(targetRef).toString()));
                xcontext.getWiki().saveDocument(translatedDraftDoc, message, false, xcontext);
                LOGGER.debug(defaultMessage);
            } finally {
                xcontext.setLocale(origLocale);
            }
        }

        return draftDocRef;
    }

    @Override
    public void setupDraftAccess(XWikiDocument document, XWikiContext xcontext) throws XWikiException
    {
        BaseObject workflowObj = document.getXObject(PUBLICATION_WORKFLOW_CLASS);
        setupDraftAccess(document, workflowObj, xcontext);
    }

    private void setupDraftAccess(XWikiDocument document, BaseObject workflow, XWikiContext xcontext)
        throws XWikiException
    {
        document.setHidden(true);

        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);

        if (wfConfig != null) {
            String contributors = publicationRoles.getContributors(wfConfig, xcontext);
            String moderators = publicationRoles.getModerators(wfConfig, xcontext);
            String validators = publicationRoles.getValidators(wfConfig, xcontext);
            String viewers = publicationRoles.getViewers(wfConfig, xcontext);
            String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

            // give the view and edit right to contributors, moderators and validators
            fillRightsObject(document, Arrays.asList("edit", "comment", "view"),
                Arrays.asList(contributors, moderators, validators), Arrays.<String> asList(), true, 0, xcontext);
            fillRightsObject(document, Arrays.asList("view"),
                Arrays.asList(viewers), Arrays.<String> asList(), true, 1, xcontext);
            fillRightsObject(document, Arrays.asList("comment", "view"),
                Arrays.asList(commenters), Arrays.<String> asList(), true, 2, xcontext);
            // and remove the rest of the rights
            removeRestOfRights(document, 3, xcontext);
        }
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
        String defaultMessage =
            "Started workflow " + workflowConfig + " on document " + stringSerializer.serialize(docName);
        String message =
            this.getMessage("workflow.save.start", defaultMessage,
                Arrays.asList(workflowConfig.toString(), stringSerializer.serialize(docName).toString()));
        xcontext.getWiki().saveDocument(doc, message, true, xcontext);
        LOGGER.info(defaultMessage);

        return true;
    }
    
    @Override
    public boolean startWorkflowAsTarget(DocumentReference docName, String workflowConfig, XWikiContext xcontext)
        throws XWikiException
    {
        XWikiDocument doc = xcontext.getWiki().getDocument(docName, xcontext);

        // check that the document is no already under workflow
        if (this.isWorkflowDocument(doc, xcontext)) {
            // TODO: put this error on the context
            return false;
        }

        // Check that the target is free. i.e. no other workflow document targets this target
        if (this.getDraftDocument(docName, xcontext) != null) {
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
        workflowObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(docName, docName), xcontext);
        // mark document as target
        workflowObject.set(WF_IS_TARGET_FIELDNAME, 1, xcontext);
        workflowObject.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
        
        //there are no rights settings on published documents, as per the rule of workflow 

        // save the document prepared like this
        String defaultMessage =
            "Started workflow " + workflowConfig + " on document " + stringSerializer.serialize(docName) + " as target";
        String message =
            this.getMessage("workflow.save.startastarget", defaultMessage,
                Arrays.asList(workflowConfig.toString(), stringSerializer.serialize(docName).toString()));
        xcontext.getWiki().saveDocument(doc, message, true, xcontext);
        LOGGER.info(defaultMessage);

        return true;
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
        String viewers = publicationRoles.getViewers(wfConfig, xcontext);
        String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

        // give the view and edit right to moderators and validators ...
        fillRightsObject(doc, Arrays.asList("edit", "comment", "view"), Arrays.asList(moderators, validators),
            Arrays.<String> asList(), true, 0, xcontext);
        // ... and only view for contributors
        fillRightsObject(doc, Arrays.asList("view"), Arrays.asList(contributors), Arrays.<String> asList(), true, 1,
            xcontext);
        fillRightsObject(doc, Arrays.asList("view"),
            Arrays.asList(viewers), Arrays.<String> asList(), true, 2, xcontext);
        fillRightsObject(doc, Arrays.asList("comment", "view"),
            Arrays.asList(commenters), Arrays.<String> asList(), true, 3, xcontext);
        // and remove the rest of the rights
        removeRestOfRights(doc, 4, xcontext);
        
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);

        // save the doc.
        // TODO: prevent the save protection from being executed, when it would be implemented

        // save the document prepared like this
        String defaultMessage = "Submitted document " + stringSerializer.serialize(document) + " for moderation ";
        String message =
            this.getMessage("workflow.save.submitForModeration", defaultMessage,
                Arrays.asList(stringSerializer.serialize(document).toString()));
        xcontext.getWiki().saveDocument(doc, message, true, xcontext);

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
       
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the document prepared like this
        String defaultMessage = "Refused moderation : " + reason;
        String message = getMessage("workflow.save.refuseModeration", defaultMessage, Arrays.asList(reason));
        xcontext.getWiki().saveDocument(doc, message, false, xcontext);

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
        
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);      
        
        // and put the rights
        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
        String validators = publicationRoles.getValidators(wfConfig, xcontext);
        String contributors = publicationRoles.getContributors(wfConfig, xcontext);
        String moderators = publicationRoles.getModerators(wfConfig, xcontext);
        String viewers = publicationRoles.getViewers(wfConfig, xcontext);
        String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

        // give the view and edit right to validators ...
        fillRightsObject(doc, Arrays.asList("edit", "comment", "view"), Arrays.asList(validators),
            Arrays.<String> asList(), true, 0, xcontext);
        // ... and only view for contributors and moderators
        fillRightsObject(doc, Arrays.asList("view"), Arrays.asList(moderators, contributors), Arrays.<String> asList(),
            true, 1, xcontext);
        fillRightsObject(doc, Arrays.asList("view"),
            Arrays.asList(viewers), Arrays.<String> asList(), true, 2, xcontext);
        fillRightsObject(doc, Arrays.asList("comment", "view"),
            Arrays.asList(commenters), Arrays.<String> asList(), true, 3, xcontext);
        // remove the rest of the rights, if any
        removeRestOfRights(doc, 4, xcontext);

        // save the doc.
        // TODO: prevent the save protection from being executed.

        // save the document prepared like this
        String defaultMessage = "Submitted document " + stringSerializer.serialize(document) + "for validation.";
        String message =
            getMessage("workflow.save.submitForValidation", defaultMessage,
                Arrays.asList(stringSerializer.serialize(document).toString()));
        xcontext.getWiki().saveDocument(doc, message, true, xcontext);

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
        
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the document prepared like this
        String defaultMessage = "Refused publication : " + reason;
        String message = getMessage("workflow.save.refuseValidation", defaultMessage, Arrays.asList(reason));
        xcontext.getWiki().saveDocument(doc, message, false, xcontext);

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
        
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // rights stay the same, only validator has the right to edit the document in the valid state, all other
        // participants to workflow can view it.

        // save the document prepared like this
        String defaultMessage = "Marked document " + stringSerializer.serialize(document) + " as valid.";
        String message =
            getMessage("workflow.save.validate", defaultMessage,
                Arrays.asList(stringSerializer.serialize(document).toString()));
        xcontext.getWiki().saveDocument(doc, message, true, xcontext);
        LOGGER.info(defaultMessage);

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
        XWikiDocument targetDocument = xcontext.getWiki().getDocument(targetRef, xcontext);

        // TODO: handle checking if the target document is free...

        final Locale origLocale = xcontext.getLocale();
        List<Locale> locales = doc.getTranslationLocales(xcontext);
        List<Locale> publishedLocales = targetDocument.getTranslationLocales(xcontext);
        locales.add(0, doc.getDefaultLocale());

        for (Locale locale : locales) {
            XWikiDocument translatedNewDocument = copyTranslatedDocument(doc, targetDocument, locale, xcontext);

            // published document is visible
            translatedNewDocument.setHidden(false);

            if (locale.equals(doc.getDefaultLocale())) {
                // setup the workflow and target flag, if a workflow doesn't exist already - only needs to be done for default locale
                BaseObject newWorkflow = targetDocument.getXObject(PUBLICATION_WORKFLOW_CLASS);
                if (newWorkflow == null) {
                    newWorkflow = targetDocument.newXObject(PUBLICATION_WORKFLOW_CLASS, xcontext);
                    newWorkflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
                    newWorkflow.set(WF_IS_TARGET_FIELDNAME, 1, xcontext);
                    newWorkflow.set(WF_TARGET_FIELDNAME, target, xcontext);
                    newWorkflow.set(WF_CONFIG_REF_FIELDNAME, workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
                }
            }

            // TODO: figure out who should be the author of the published document

            // save the published document prepared like this
            String defaultMessage = "Published new version of the document.";
            try {
                xcontext.setLocale(translatedNewDocument.getRealLocale());
                String message = getMessage("workflow.save.publishNew", defaultMessage, null);
                // setup the context to let events know that they are in the publishing context
                xcontext.put(CONTEXTKEY_PUBLISHING, true);
                xcontext.getWiki().saveDocument(translatedNewDocument, message, false, xcontext);
                LOGGER.debug(defaultMessage + (locale.equals(doc.getDefaultLocale()) ? "" : " (in locale "+locale+")"));
            } finally {
                xcontext.remove(CONTEXTKEY_PUBLISHING);
                xcontext.setLocale(origLocale);
            }
        }
        // remove the languages which are not anymore
        publishedLocales.removeAll(locales);
        for (Locale toRemove: publishedLocales) {
            XWikiDocument translatedDocumentToRemove = targetDocument.getTranslatedDocument(toRemove, xcontext);
            if (translatedDocumentToRemove != targetDocument) {
                xcontext.getWiki().deleteDocument(translatedDocumentToRemove, xcontext);
                LOGGER.debug("deleted published target {} in locale {}", stringSerializer.serialize(targetRef), toRemove);
            }
        }

        // prepare the draft document as well (objects only, so default locale is good enough)
        // set the status
        workflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);

        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the the draft document prepared like this
        String defaultMessage2 = "Published this document to " + stringSerializer.serialize(document) + ".";
        String message2 =
            getMessage("workflow.save.publishDraft", defaultMessage2,
                Arrays.asList(stringSerializer.serialize(targetRef).toString()));
        xcontext.getWiki().saveDocument(doc, message2, false, xcontext);
        LOGGER.info(defaultMessage2);

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
                if (STATUS_PUBLISHED.equals(draftStatus)) // If status is published, change draft status back to draft
                {
                    // make the draft doc draft again
                    makeDocumentDraft(draftDoc, workflow, xcontext);
                    // save the draft document
                    String defaultMessage =
                        "Created draft from published document" + stringSerializer.serialize(document) + ".";
                    String message =
                        getMessage("workflow.save.unpublish", defaultMessage,
                            Arrays.asList(stringSerializer.serialize(document).toString()));
                    xcontext.getWiki().saveDocument(draftDoc, message, true, xcontext);
                    LOGGER.info(defaultMessage);
                }
            } else {
                // the existing draft is not published and force to draft is required
                // copy the contents from target to draft
                try {
                    // TODO: do this for all the languages of document to copy from, and remove the languages which are
                    // not anymore
                    this.copyContentsToNewVersion(targetDoc, draftDoc, xcontext);
                } catch (IOException e) {
                    throw new XWikiException(XWikiException.MODULE_XWIKI_DOC, XWikiException.ERROR_XWIKI_UNKNOWN,
                        "Error accessing attachments when copying document "
                            + stringSerializer.serialize(targetDoc.getDocumentReference()) + " to document "
                            + stringSerializer.serialize(draftDoc.getDocumentReference()), e);
                }
                // make the draft doc draft again
                makeDocumentDraft(draftDoc, workflow, xcontext);
                // save the draft document
                String defaultMessage =
                    "Created draft from published document" + stringSerializer.serialize(document) + ".";
                String message =
                    getMessage("workflow.save.unpublish", defaultMessage,
                        Arrays.asList(stringSerializer.serialize(document).toString()));
                xcontext.getWiki().saveDocument(draftDoc, message, true, xcontext);
                LOGGER.info(defaultMessage);
            }
        } else {
            draftDocRef = this.createDraftDocument(targetDoc, xcontext);
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
    public boolean editDraft(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);
        BaseObject workflow = doc.getXObject(PUBLICATION_WORKFLOW_CLASS);
        String draftStatus = workflow.getStringValue(WF_STATUS_FIELDNAME);
        if (draftStatus.equals(STATUS_PUBLISHED)) {
            makeDocumentDraft(doc, workflow, xcontext);
            String defaultMessage = "Back to draft status to enable editing.";
            String message = getMessage("workflow.save.backToDraft", defaultMessage, null);
            xcontext.getWiki().saveDocument(doc, message, true, xcontext);
            return true;
        } else
            return false;
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
        
        // Add the author in order to keep track of the person who change the status
        publishedWorkflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        publishedDoc.setHidden(true);

        // save it
        String defaultMessage = "Archived document.";
        String message = getMessage("workflow.save.archive", defaultMessage, null);
        xcontext.getWiki().saveDocument(publishedDoc, message, true, xcontext);
        LOGGER.info(defaultMessage + " " + stringSerializer.serialize(document));

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
        
        // Add the author in order to keep track of the person who change the status
        archivedWorkflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        archivedDoc.setHidden(false);

        // save it
        String defaultMessage = "Published document from an archive.";
        String message = getMessage("workflow.save.publishFromArchive", defaultMessage, null);
        xcontext.getWiki().saveDocument(archivedDoc, message, true, xcontext);
        LOGGER.info(defaultMessage + " " + stringSerializer.serialize(document));

        return true;
    }

    /**
     * copy over the contents in the given locale of the source document to the target document.
     * All arguments must not be null.
     * The source document should already have a variant for the given locale.
     * If the target does not have a variant, it will be created.
     * This helper does not save the created / changed document variant; this must be done by the caller
     * (possibly after some modifications).
     * @return the created / changed target locale variant
     */
    private XWikiDocument copyTranslatedDocument(XWikiDocument sourceDocument, XWikiDocument targetDocument,
            Locale locale, XWikiContext xcontext) throws XWikiException
    {
        XWikiDocument translatedTargetDocument;
        try {
            XWikiDocument translatedSourceDocument = sourceDocument.getTranslatedDocument(locale, xcontext);
            translatedTargetDocument = targetDocument.getTranslatedDocument(locale, xcontext);
            if (!locale.equals(sourceDocument.getDefaultLocale()) && translatedTargetDocument == targetDocument) {
                // the language variant does not exist yet; make a copy ...
                translatedTargetDocument = translatedSourceDocument.duplicate(targetDocument.getDocumentReference());
                // ... and start from version 1.1 (copy from XWiki#copyDocument; feels pretty fishy here but needed to start with version 1.1)
                translatedTargetDocument.setNew(true);
                translatedTargetDocument.setVersion("1.1");
            }
            // now the language variant exists; do a merge
            this.copyContentsToNewVersion(translatedSourceDocument, translatedTargetDocument, xcontext);
        } catch (IOException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_DOC, XWikiException.ERROR_XWIKI_UNKNOWN,
                    "Error accessing attachments when copying document " + stringSerializer.serialize(sourceDocument.getDocumentReference())
                    + " to document " + stringSerializer.serialize(targetDocument.getDocumentReference()), e);
        }
        return translatedTargetDocument;
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
     * @throws IOException
     */
    protected boolean copyContentsToNewVersion(XWikiDocument fromDocument, XWikiDocument toDocument, XWikiContext xcontext)
        throws XWikiException, IOException
    {
        // use a fake 3 way merge: previous is toDocument without comments, rights and wf object
        // current version is current toDocument
        // next version is fromDocument without comments, rights and wf object
        XWikiDocument previousDoc = toDocument.clone();
        this.cleanUpIrrelevantDataFromDoc(previousDoc, xcontext);
        // set reference and language

        // make sure that the attachments are properly loaded in memory for the duplicate to work fine, otherwise it's a
        // bit impredictable about attachments
        fromDocument.loadAttachments(xcontext);
        XWikiDocument nextDoc = fromDocument.duplicate(toDocument.getDocumentReference());
        this.cleanUpIrrelevantDataFromDoc(nextDoc, xcontext);

        // and now merge. Normally the attachments which are not in the next doc are deleted from the current doc
        MergeResult result = toDocument.merge(previousDoc, nextDoc, new MergeConfiguration(), xcontext);

        // for some reason the creator doesn't seem to be copied if the toDocument is new, so let's put it
        if (toDocument.isNew()) {
            toDocument.setCreatorReference(fromDocument.getCreatorReference());
        }
        // Author does not seem to be merged anymore in the merge function in newer versions, so we'll do it here
        toDocument.setAuthorReference(fromDocument.getAuthorReference());

        // language is not handled by the merge result at all, let's set default language (TODO: check if language and
        // translation flag should also be set)
        toDocument.setDefaultLocale(fromDocument.getDefaultLocale());

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
     * Cleans up the irrelevant data from the passed document, for merge and comparison between draft document and
     * published document. This function alters its parameter. By default it removes rights objects, comments, and the
     * publication workflow document.
     * 
     * @param document the document to clean up irrelevant data from, it alters its parameter.
     */
    protected void cleanUpIrrelevantDataFromDoc(XWikiDocument document, XWikiContext xcontext)
    {
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(COMMENTS_CLASS,
            document.getDocumentReference()));
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(RATINGS_CLASS,
            document.getDocumentReference()));
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(AVERAGE_RATINGS_CLASS,
            document.getDocumentReference()));
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(RIGHTS_CLASS,
            document.getDocumentReference()));
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(PUBLICATION_WORKFLOW_CLASS,
            document.getDocumentReference()));
    }

    /**
     * Turns a document in a draft document by setting the appropriate rights, hidden, settings in the workflow object.
     * 
     * @param doc
     * @param workflow
     * @param xcontext
     * @throws XWikiException
     */
    protected void makeDocumentDraft(XWikiDocument doc, BaseObject workflow, XWikiContext xcontext) throws XWikiException
    {
        BaseObject workflowObj = workflow;
        if (workflowObj == null) {
            workflowObj = doc.getXObject(PUBLICATION_WORKFLOW_CLASS);
        }

        workflowObj.set(WF_STATUS_FIELDNAME, STATUS_DRAFT, xcontext);
        
        // Add the author in order to keep track of the person who change the status
        workflowObj.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        workflowObj.set(WF_IS_TARGET_FIELDNAME, 0, xcontext);

        // and setup draft which will handle visibility and rights
        setupDraftAccess(doc, workflowObj, xcontext);
    }

    protected BaseObject validateWorkflow(XWikiDocument document, List<String> expectedStatuses,
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

    /**
     * Fills the n-th non-null rights object in this document with the passed settings. <br />
     * NOTE that n is not actually the object number, but an ordinal: first, second, etc among all the non-null objects.
     * It is however 0-based. We need to proceed like this for rights setup because otherwise (remove all objects, add
     * objects) object numbers are incremented and this can turn out to be pretty bad for performance, since empty slots
     * are filled with nulls. <br/>
     * If such an object does not exist, the function will create one and fill it in. <br />
     * To setup multiple sets of rights on a document, use this function multiple times with incrementing values,
     * starting on 0, for the n argument.
     * 
     * @param document
     * @param levels
     * @param groups
     * @param users
     * @param allowdeny
     * @param n
     * @param context
     * @throws XWikiException
     */
    protected void fillRightsObject(XWikiDocument document, List<String> levels, List<String> groups, List<String> users,
        boolean allowdeny, int n, XWikiContext context) throws XWikiException
    {
        // create a new object of type xwiki rights
        BaseObject rightsObject = getNonNullRightsObject(document, n, context);
        // put the rights and create
        rightsObject.set(RIGHTS_ALLOWDENY, allowdeny ? 1 : 0, context);
        // prepare the value for the groups property: it's a bit uneasy, we cannot pass a list to the BaseObject.set
        // and
        // to build the string we either need to know the separator, or we need to do this bad workaround to make
        // GroupsClass build the property value
        PropertyClass groupsPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_GROUPS);
        BaseProperty<?> groupsProperty = groupsPropClass.fromStringArray((String[]) groups.toArray());
        rightsObject.set(RIGHTS_GROUPS, groupsProperty.getValue(), context);
        PropertyClass usersPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_USERS);
        BaseProperty<?> usersProperty = usersPropClass.fromStringArray((String[]) users.toArray());
        rightsObject.set(RIGHTS_USERS, usersProperty.getValue(), context);
        PropertyClass levelsPropClass = (PropertyClass) rightsObject.getXClass(context).get(RIGHTS_LEVELS);
        BaseProperty<?> levelsProperty = levelsPropClass.fromStringArray((String[]) levels.toArray());
        rightsObject.set(RIGHTS_LEVELS, levelsProperty.getValue(), context);
    }

    /**
     * @param document
     * @param index
     * @param xcontext
     * @return the object of type rights with ordinal 'index' in the list of rights objects of the passed document or a
     *         new object if none is found. If the first non-null object is needed, the passed index needs to be 0
     *         regardless of the actual object number of that rights object. If the second object is needed, the passed
     *         index should be 1, etc.
     * @throws XWikiException
     */
    protected BaseObject getNonNullRightsObject(XWikiDocument document, int index, XWikiContext xcontext)
        throws XWikiException
    {
        int nonNullIndex = 0;
        List<BaseObject> rightObjects = document.getXObjects(RIGHTS_CLASS);
        if (rightObjects != null) {
            for (BaseObject rObj : rightObjects) {
                if (rObj != null) {
                    if (nonNullIndex == index) {
                        return rObj;
                    }
                    nonNullIndex++;
                }
            }
        }
        return document.newXObject(RIGHTS_CLASS, xcontext);
    }

    /**
     * Removes all non-null objects of type rights of this document, starting with the one with index
     * {@code startingWith}. Note that startingWith is not an actual object number, but an ordinal: only non-null
     * objects are counted.
     * 
     * @param document
     * @param startingWith
     * @param context
     * @throws XWikiException
     */
    protected void removeRestOfRights(XWikiDocument document, int startingWith, XWikiContext context)
        throws XWikiException
    {
        // if starting with is smaller or equal to 0, remove all.
        if (startingWith <= 0) {
            document.removeXObjects(explicitReferenceDocRefResolver.resolve(RIGHTS_CLASS,
                document.getDocumentReference()));
            return;
        }

        int nonNullIndex = 0;
        List<BaseObject> objects = document.getXObjects(RIGHTS_CLASS);
        if (objects == null) {
            // yey, nothing to do
            return;
        }
        // for all the positions in the objects array...
        for (int i = 0; i < objects.size(); i++) {
            // ... get the object
            BaseObject rObj = objects.get(i);
            if (rObj != null) {
                // ... if it's not null
                if (nonNullIndex >= startingWith) {
                    // ... and the index is higher than the starting position, remove it
                    document.removeXObject(rObj);
                }
                // ... increment the non null index
                nonNullIndex++;
            }
        }
    }

    /**
     * @return the xwiki context from the execution context
     */
    protected XWikiContext getXContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
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
}
