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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
import org.xwiki.contrib.rights.RightsWriter;
import org.xwiki.contrib.rights.RulesObjectWriter;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.logging.LogLevel;
import org.xwiki.logging.event.LogEvent;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceProvider;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.SpaceReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.observation.ObservationManager;
import org.xwiki.properties.converter.Converter;
import org.xwiki.security.authorization.ReadableSecurityRule;
import org.xwiki.security.authorization.Right;
import org.xwiki.security.authorization.RuleState;
import org.xwiki.workflowpublication.DocumentChildPublishingEvent;
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
import com.xpn.xwiki.objects.ObjectDiff;

/**
 * @version $Id$
 */
@Component
public class DefaultPublicationWorkflow implements PublicationWorkflow
{
    public static final String WF_CONFIG_REF_FIELDNAME = "workflow";

    /**
     * PublicationWorkflowClass field storing whether the workflow encompasses descendants or not.
     */
    public static final String WF_INCLUDE_CHILDREN_FIELDNAME = "includeChildren";

    public static final String WF_TARGET_FIELDNAME = "target";

    public final static String WF_STATUS_FIELDNAME = "status";
    
    public final static String WF_STATUS_AUTHOR_FIELDNAME = "statusAuthor";

    public final static String WF_IS_TARGET_FIELDNAME = "istarget";

    public final static String WF_IS_DRAFTSPACE_FIELDNAME = "defaultDraftSpace";

    public static final String WF_PUBLICATION_COMMENT_FIELDNAME = "publicationComment";

    public static final String WF_CONFIG_CLASS_HIDEDRAFT_FIELDNAME = "draftsHidden";

    public static final String WF_CONFIG_CLASS_ALLOW_CUSTOM_PUBLICATION_COMMENT = "allowCustomPublicationComment";

    public static final String WF_CONFIG_CLASS_SKIP_DRAFT_RIGHTS = "skipDraftRights";

    public final static int DRAFT = 0;

    public final static int PUBLISHED = 1;

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_VALID = "valid";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    public final static String STATUS_ARCHIVED = "archived";

    public final static String CONTEXTKEY_PUBLISHING = "publicationworkflow:publish";

    public static final String DEFAULT_PUBLICATION_COMMENT = "Published new version of the document by {0}.";

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

    public static final String XWIKI_SPACE = "XWiki";

    /**
     * The reference to the xwiki rights, relative to the current wiki. <br />
     */
    public static final EntityReference RIGHTS_CLASS = new EntityReference("XWikiRights", EntityType.DOCUMENT,
        new EntityReference(XWIKI_SPACE, EntityType.SPACE));

    public static final EntityReference GLOBAL_RIGHTS_CLASS = new EntityReference("XWikiGlobalRights",
        EntityType.DOCUMENT, new EntityReference(XWIKI_SPACE, EntityType.SPACE));

    public static final String WEB_PREFERENCES = "WebPreferences";

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
    @Named("current")
    protected SpaceReferenceResolver<String> explicitStringSpaceRefResolver;

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
     * Used to send document child publishing events.
     */
    @Inject
    protected ObservationManager observationManager;

    /**
     * EntityReference provider used to get default EntityReference names.
     */
    @Inject
    protected EntityReferenceProvider defaultEntityReferenceProvider;

    /**
     * Used to generate {@link org.xwiki.security.authorization.SecurityRule} on workflow transitions.
     */
    @Inject
    protected RightsWriter rightsWriter;

    /**
     * Allows to set rules on page objects without saving them to the database yet.
     */
    @Inject
    @Named("recycling")
    private RulesObjectWriter rulesObjectWriter;

    /**
     * Used to retrieve page children using the reference hierarchy.
     */
    @Inject
    @Named("nestedPages")
    private org.xwiki.tree.Tree tree;

    /**
     * Used to convert an EntityReference into a {@link org.xwiki.tree.TreeNode} id.
     */
    @Inject
    @Named("entityTreeNodeId")
    private Converter<EntityReference> entityTreeNodeIdConverter;

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.workflowpublication.PublicationWorkflow#isWorkflowDocument(com.xpn.xwiki.doc.XWikiDocument,
     *      com.xpn.xwiki.XWikiContext)
     */
    @Override
    public boolean isWorkflowDocument(XWikiDocument document, XWikiContext context)
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
        if (!new HashSet<>(fromLocales).equals(new HashSet<>(toLocales))) {
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
            if (!contentDiffs.isEmpty()) {
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
        // 1. metadata diffs, other than document author
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
        if (!objectDiffs.isEmpty()) {
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
            XWikiAttachment fromAttachment = previousDoc.getAttachment(toAttachment.getFilename());
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
            if (fromAttachment.getLongSize() != toAttachment.getLongSize()) {
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
                LOGGER.warn("could not load attachment {}; assume they differ in contents",
                    fromAttachment.getFilename(), ioe);
            }
            // unload the content of the attachments after comparison, since we don't need it anymore, and we don't
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
        List<String> results;
        // query on the passed database
        String originalDatabase = xcontext.getWikiId();
        try {
            xcontext.setWikiId(wiki);
            results = xcontext.getWiki().getStore().search(workflowsQuery, 0, 0, params, xcontext);
        } finally {
            xcontext.setWikiId(originalDatabase);
        }

        if (results == null || results.isEmpty()) {
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

        // We can only create a draft for a published document, from the published or archived state.
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
        String defaultDraftSpace = wfConfig.getStringValue(WF_IS_DRAFTSPACE_FIELDNAME).trim();
        String defaultTargetSpace = wfConfig.getStringValue("defaultTargetSpace").trim();
        if (StringUtils.isEmpty(defaultDraftSpace) || StringUtils.isEmpty(defaultTargetSpace)) {
            // TODO: put exception on the context
            return null;
        }
        SpaceReference draftSpaceRef =
            getDraftSpaceReference(xcontext, defaultDraftSpace, targetRef, defaultTargetSpace);

        // Get a new document in the drafts space, starting with the name of the target document.
        String draftDocName = xcontext.getWiki()
            .getUniquePageName(stringSerializer.serialize(draftSpaceRef), targetRef.getName(), xcontext);
        DocumentReference draftDocRef = new DocumentReference(draftDocName, draftSpaceRef);
        XWikiDocument draftDoc = xcontext.getWiki().getDocument(draftDocRef, xcontext);

        final Locale origLocale = xcontext.getLocale();
        XWikiDocument translatedDraftDoc;
        List<Locale> locales = targetDocument.getTranslationLocales(xcontext);
        locales.add(0, targetDocument.getDefaultLocale());

        boolean includeChildren = targetDocument.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1;
        String defaultMessage = String.format("Created draft for %s.",stringSerializer.serialize(targetRef));
        String message = getMessage("workflow.save.createDraft", defaultMessage,
            Collections.singletonList(stringSerializer.serialize(targetRef)));

        for (Locale locale : locales) {
            translatedDraftDoc = copyTranslatedDocument(targetDocument, draftDoc, locale, xcontext);

            // Workflow object: only needs to be set up for default locale.
            if (locale.equals(targetDocument.getDefaultLocale())) {
                BaseObject draftWfObject = draftDoc.newXObject(
                    explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS,
                        draftDocRef), xcontext);
                draftWfObject.set(WF_INCLUDE_CHILDREN_FIELDNAME, includeChildren ? 1 : 0, xcontext);
                draftWfObject.set(WF_CONFIG_REF_FIELDNAME,
                    compactWikiSerializer.serialize(wfConfig.getDocumentReference(), draftDocRef), xcontext);
                draftWfObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(targetRef, draftDocRef),
                    xcontext);
                this.makeDocumentDraft(draftDoc, draftWfObject, xcontext);
            }

            // Set up the creator to the current user.
            translatedDraftDoc.setCreatorReference(xcontext.getUserReference());
            // And save the document.

            try {
                xcontext.setLocale(translatedDraftDoc.getRealLocale());

                saveDocumentWithoutRightsCheck(translatedDraftDoc, message, false, xcontext);
                LOGGER.debug(defaultMessage);
            } finally {
                xcontext.setLocale(origLocale);
            }
        }

        // Copy the page children if the "includeChildren" argument is true, and remove the obsolete ones.
        if (includeChildren) {
            List<DocumentReference> children = getChildren(targetRef);
            // Retrieve all draft's children and keep only the ones that do not have a counterpart in the target.
            // Delete the obsolete ones.
            List<DocumentReference> obsoleteDraftChildren = getChildren(draftDocRef);
            for (DocumentReference child : children) {
                DocumentReference childTarget = getChildTarget(child, targetRef, draftDocRef);
                copyDocument(child, childTarget, targetRef, xcontext.getUserReference(), true, message);
                obsoleteDraftChildren.remove(childTarget);
            }
            // Delete draft's children without a counterpart in the target.
            for (DocumentReference obsoletePublishedChild : obsoleteDraftChildren) {
                XWikiDocument obsoletePublishedChildDocument =
                    xcontext.getWiki().getDocument(obsoletePublishedChild, xcontext);
                xcontext.getWiki().deleteDocument(obsoletePublishedChildDocument, xcontext);
            }
        }

        return draftDocRef;
    }

    private SpaceReference getDraftSpaceReference(XWikiContext xcontext, String defaultDraftsSpace,
        DocumentReference targetRef, String defaultTargetSpace)
    {
        boolean isNonTerminalPage = targetRef.getName().equals(
            xcontext.getWiki().getXWikiPreference("xwiki.defaultpage", "WebHome", xcontext));
        SpaceReference draftSpaceRef = explicitStringSpaceRefResolver.resolve(defaultDraftsSpace);
        if (isNonTerminalPage) {
            List<String> draftSpacesHierarchy = getDraftSpacesHierarchy(targetRef, defaultTargetSpace, defaultDraftsSpace);
            draftSpaceRef = new SpaceReference(xcontext.getWikiId(), draftSpacesHierarchy);
        }
        return draftSpaceRef;
    }

    /**
     * Get the draft spaces hierarchy by replacing the default target space with the default draft space.
     */
    private static List<String> getDraftSpacesHierarchy(DocumentReference targetRef, String defaultTargetSpace,
        String defaultDraftsSpace)
    {
        List<String> spaces = new ArrayList<>();
        for (EntityReference reference : targetRef.getReversedReferenceChain()) {
            if (EntityType.SPACE.equals(reference.getType())) {
                if (reference.getName().equals(defaultTargetSpace)) {
                    spaces.add(defaultDraftsSpace);
                } else {
                    spaces.add(reference.getName());
                }
            }
        }
        return spaces;
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
        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);

        if (wfConfig != null) {
            // Update draft rights, only if option "skipDraftRights" is inactive
            if (wfConfig.getIntValue(WF_CONFIG_CLASS_SKIP_DRAFT_RIGHTS, 0) == 0) {
                String contributors = publicationRoles.getContributors(wfConfig, xcontext);
                String moderators = publicationRoles.getModerators(wfConfig, xcontext);
                String validators = publicationRoles.getValidators(wfConfig, xcontext);
                String viewers = publicationRoles.getViewers(wfConfig, xcontext);
                String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

                // give the view and edit right to contributors, moderators and validators
                List<ReadableSecurityRule> rules = new ArrayList<>();
                rules.add(rightsWriter.createRule(toDocumentReferenceList(Arrays.asList(contributors, moderators,
                    validators), document.getDocumentReference()), null, Arrays.asList(Right.VIEW, Right.COMMENT,
                    Right.EDIT), RuleState.ALLOW));
                rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(viewers),
                    document.getDocumentReference()), null, Collections.singletonList(Right.VIEW), RuleState.ALLOW));
                rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(commenters),
                    document.getDocumentReference()), null, Arrays.asList(Right.VIEW, Right.COMMENT), RuleState.ALLOW));
                persistAndMaybeSaveRules(document, rules, workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1);
            }

            if (wfConfig.getIntValue(WF_CONFIG_CLASS_HIDEDRAFT_FIELDNAME, 1) == 1) {
                document.setHidden(true);
                if (workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1) {
                    // TODO: provide contextual message
                    updateChildrenHiddenStatus(document.getDocumentReference(), true, null);
                }
            }
        } else {
            // b/w compat
            document.setHidden(true);
        }
    }

    @Override
    public boolean startWorkflow(DocumentReference docName, String workflowConfig, DocumentReference target,
        XWikiContext xcontext) throws XWikiException
    {
        return this.startWorkflow(docName, false, workflowConfig, target, xcontext);
    }

    @Override
    public boolean startWorkflow(DocumentReference docName, boolean includeChildren, String workflowConfig,
        DocumentReference target, XWikiContext xcontext) throws XWikiException
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

        workflowObject.set(WF_INCLUDE_CHILDREN_FIELDNAME, includeChildren ? 1 : 0, xcontext);
        workflowObject.set(WF_CONFIG_REF_FIELDNAME, workflowConfig, xcontext);
        workflowObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(target, docName), xcontext);

        makeDocumentDraft(doc, workflowObject, xcontext);

        // save the document prepared like this
        String defaultMessage =
            "Started workflow " + workflowConfig + " on document " + stringSerializer.serialize(docName);
        String message =
            this.getMessage("workflow.save.start", defaultMessage,
                Arrays.asList(workflowConfig, stringSerializer.serialize(docName)));
        saveDocumentWithoutRightsCheck(doc, message, true, xcontext);
        LOGGER.info(defaultMessage);

        return true;
    }

    @Override
    public boolean startWorkflowAsTarget(DocumentReference targetRef, String workflowConfig, XWikiContext xcontext)
        throws XWikiException
    {
        return startWorkflowAsTarget(targetRef, true, workflowConfig, xcontext);
    }

    @Override
    public boolean startWorkflowAsTarget(DocumentReference targetRef, boolean includeChildren, String workflowConfig,
        XWikiContext xcontext) throws XWikiException
    {
        XWikiDocument targetDoc = xcontext.getWiki().getDocument(targetRef, xcontext);

        // Check that the document is no already under workflow.
        if (this.isWorkflowDocument(targetDoc, xcontext)) {
            // TODO: put this error on the context
            return false;
        }

        // Check that the target is free. i.e. no other workflow document targets this target.
        if (this.getDraftDocument(targetRef, xcontext) != null) {
            // TODO: put this error on the context
            return false;
        }

        BaseObject workflowObject = targetDoc.newXObject(
            explicitReferenceDocRefResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS, targetRef),
            xcontext);
        BaseObject wfConfig = configManager.getWorkflowConfig(workflowConfig, xcontext);
        if (wfConfig == null) {
            // TODO: put error on the context
            return false;
        }

        workflowObject.set(WF_INCLUDE_CHILDREN_FIELDNAME, includeChildren ? 1 : 0, xcontext);
        workflowObject.set(WF_CONFIG_REF_FIELDNAME, workflowConfig, xcontext);
        workflowObject.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(targetRef, targetRef), xcontext);
        // Mark document as target.
        workflowObject.set(WF_IS_TARGET_FIELDNAME, 1, xcontext);
        workflowObject.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);

        // There are no rights settings on published documents, as per the rule of workflow.

        // Save the document prepared like this.
        String defaultMessage = String.format("Started workflow %s on document %s as target", workflowConfig,
            stringSerializer.serialize(targetRef));
        String message =
            this.getMessage("workflow.save.startAsTarget", defaultMessage,
                Arrays.asList(workflowConfig, stringSerializer.serialize(targetRef)));
        saveDocumentWithoutRightsCheck(targetDoc, message, true, xcontext);

        LOGGER.info(defaultMessage);

        return true;
    }

    @Override
    public boolean submitForModeration(DocumentReference document) throws XWikiException
    {

        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Collections.singletonList(STATUS_DRAFT), DRAFT, xcontext);
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
        // and put the rights, only if option "skipDraftRights" is inactive
        if (wfConfig.getIntValue(WF_CONFIG_CLASS_SKIP_DRAFT_RIGHTS, 0) == 0) {
            String validators = publicationRoles.getValidators(wfConfig, xcontext);
            String contributors = publicationRoles.getContributors(wfConfig, xcontext);
            String viewers = publicationRoles.getViewers(wfConfig, xcontext);
            String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

            // give the view and edit right to moderators and validators ...
            List<ReadableSecurityRule> rules = new ArrayList<>();
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Arrays.asList(moderators, validators), document),
                null, Arrays.asList(Right.VIEW, Right.COMMENT, Right.EDIT), RuleState.ALLOW));
            // ... and only view for contributors
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(contributors), document), null,
                Collections.singletonList(Right.VIEW), RuleState.ALLOW));
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(viewers), document), null,
                Collections.singletonList(Right.VIEW), RuleState.ALLOW));
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(commenters), document), null,
                Arrays.asList(Right.VIEW, Right.COMMENT), RuleState.ALLOW));
            persistAndMaybeSaveRules(doc, rules, workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1);
        }

        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);

        // save the doc.
        // TODO: prevent the save protection from being executed, when it would be implemented

        // save the document prepared like this
        String defaultMessage = "Submitted document " + stringSerializer.serialize(document) + " for moderation ";
        String message =
            this.getMessage("workflow.save.submitForModeration", defaultMessage,
                Collections.singletonList(stringSerializer.serialize(document)));
        saveDocumentWithoutRightsCheck(doc, message, true, xcontext);

        return true;
    }

    @Override
    public boolean refuseModeration(DocumentReference document, String reason) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Collections.singletonList(STATUS_MODERATING), 0, xcontext);
        if (workflow == null) {
            return false;
        }

        // preconditions met, make the document back draft
        makeDocumentDraft(doc, workflow, xcontext);
       
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the document prepared like this
        String defaultMessage = "Refused moderation : " + reason;
        String message = getMessage("workflow.save.refuseModeration", defaultMessage, Collections.singletonList(reason));
        saveDocumentWithoutRightsCheck(doc, message, false, xcontext);

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
        
        // and put the rights, only if option "skipDraftRights" is inactive
        BaseObject wfConfig =
            configManager.getWorkflowConfig(workflow.getStringValue(WF_CONFIG_REF_FIELDNAME), xcontext);
        if (wfConfig.getIntValue(WF_CONFIG_CLASS_SKIP_DRAFT_RIGHTS, 0) == 0) {
            String validators = publicationRoles.getValidators(wfConfig, xcontext);
            String contributors = publicationRoles.getContributors(wfConfig, xcontext);
            String moderators = publicationRoles.getModerators(wfConfig, xcontext);
            String viewers = publicationRoles.getViewers(wfConfig, xcontext);
            String commenters = publicationRoles.getCommenters(wfConfig, xcontext);

            // give the view and edit right to validators ...
            List<ReadableSecurityRule> rules = new ArrayList<>();
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(validators), document), null,
                Arrays.asList(Right.VIEW, Right.COMMENT, Right.EDIT), RuleState.ALLOW));
            // ... and only view for contributors and moderators
            rules.add(
                rightsWriter.createRule(toDocumentReferenceList(Arrays.asList(moderators, contributors), document),
                    null, Collections.singletonList(Right.VIEW), RuleState.ALLOW));
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(viewers), document), null,
                Collections.singletonList(Right.VIEW), RuleState.ALLOW));
            rules.add(rightsWriter.createRule(toDocumentReferenceList(Collections.singletonList(commenters), document), null,
                Arrays.asList(Right.VIEW, Right.COMMENT), RuleState.ALLOW));
            persistAndMaybeSaveRules(doc, rules, workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1);
        }

        // save the doc.
        // TODO: prevent the save protection from being executed.
        // this is achieved at the moment by using the XWiki#saveDocument
        // which bypasses the check

        // save the document prepared like this
        String defaultMessage = "Submitted document " + stringSerializer.serialize(document) + "for validation.";
        String message =
            getMessage("workflow.save.submitForValidation", defaultMessage,
                Collections.singletonList(stringSerializer.serialize(document)));
        saveDocumentWithoutRightsCheck(doc, message, true, xcontext);

        return true;
    }

    @Override
    public boolean refuseValidation(DocumentReference document, String reason) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Collections.singletonList(STATUS_VALIDATING), 0, xcontext);
        if (workflow == null) {
            return false;
        }

        // preconditions met, make the document back draft
        makeDocumentDraft(doc, workflow, xcontext);
        
        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the document prepared like this
        String defaultMessage = "Refused publication : " + reason;
        String message = getMessage("workflow.save.refuseValidation", defaultMessage, Collections.singletonList(reason));
        saveDocumentWithoutRightsCheck(doc, message, false, xcontext);

        return true;
    }

    @Override
    public boolean validate(DocumentReference document) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext);

        BaseObject workflow = validateWorkflow(doc, Collections.singletonList(STATUS_VALIDATING), DRAFT, xcontext);
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
                Collections.singletonList(stringSerializer.serialize(document)));
        saveDocumentWithoutRightsCheck(doc, message, true, xcontext);
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
        DocumentReference publisher = xcontext.getUserReference();
        DocumentReference targetRef = explicitStringDocRefResolver.resolve(target, document);

        // Define if a custom publication comment should be used
        DocumentReference workflowConfig = explicitStringDocRefResolver.resolve(
            doc.getXObject(PUBLICATION_WORKFLOW_CLASS).getStringValue(WF_CONFIG_REF_FIELDNAME), document);
        XWikiDocument workflowConfigDocument = xcontext.getWiki().getDocument(workflowConfig, xcontext);
        String publicationComment = null;
        if (workflowConfigDocument.getXObject(PUBLICATION_WORKFLOW_CONFIG_CLASS)
            .getIntValue(WF_CONFIG_CLASS_ALLOW_CUSTOM_PUBLICATION_COMMENT) == 1
            && StringUtils.isNotBlank(workflow.getStringValue(WF_PUBLICATION_COMMENT_FIELDNAME))) {
            publicationComment = workflow.getStringValue(WF_PUBLICATION_COMMENT_FIELDNAME);
        }


        // Publish the workflow document and its children if the workflow scope includes the children
        boolean includeChildren = workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1;
        copyDocument(document, targetRef, targetRef, publisher, includeChildren, publicationComment);

        // prepare the draft document as well (objects only, so default locale is good enough)
        // set the status
        workflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);

        // Add the author in order to keep track of the person who change the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        // save the draft document prepared like this
        String defaultMessage2 = "Published this document to " + stringSerializer.serialize(targetRef) + ".";
        String message2 =
            getMessage("workflow.save.publishDraft", defaultMessage2,
                Collections.singletonList(stringSerializer.serialize(targetRef)));
        saveDocumentWithoutRightsCheck(doc, message2, false, xcontext);
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
                // a draft exists, and it's either in state published, which means identical as the published doc, or
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
                            Collections.singletonList(stringSerializer.serialize(document)));
                    saveDocumentWithoutRightsCheck(draftDoc, message, true, xcontext);
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
                        Collections.singletonList(stringSerializer.serialize(document)));
                saveDocumentWithoutRightsCheck(draftDoc, message, true, xcontext);

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
            saveDocumentWithoutRightsCheck(doc, message, true, xcontext);
            return true;
        } else
            return false;
    }

    @Override
    public boolean archive(DocumentReference reference) throws XWikiException
    {
        return archive(reference, true);
    }

    /**
     * Archives a document.
     * @param document a DocumentReference
     * @param isTarget true if the reference is a target, false otherwise (the reference is a draft)
     * @return
     * @throws XWikiException
     */
    protected boolean archive(DocumentReference document, boolean isTarget) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument doc = xcontext.getWiki().getDocument(document, xcontext).clone();
        DocumentReference publisher = xcontext.getUserReference();

        BaseObject workflow;
        if (isTarget) {
            workflow = validateWorkflow(doc, Collections.singletonList(STATUS_PUBLISHED), PUBLISHED, xcontext);
        } else {
            workflow = doc.getXObject(PUBLICATION_WORKFLOW_CLASS);
        }
        if (workflow == null) {
            return false;
        }

        // Preconditions are met, mark document as hidden
        workflow.set(WF_STATUS_FIELDNAME, STATUS_ARCHIVED, xcontext);
        
        // Add the author in order to keep track of the person who changed the status
        workflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        doc.setHidden(true);

        // Save document
        String defaultMessage = "Archived document by {0}.";
        String message = getMessage("workflow.save.archive", defaultMessage,
            Collections.singletonList(stringSerializer.serialize(publisher)));
        saveDocumentWithoutRightsCheck(doc, message, true, xcontext);
        LOGGER.info("{} {}", message, stringSerializer.serialize(document));

        // If workflow scope includes children, hide children as well
        if (workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1) {
            updateChildrenHiddenStatus(document, true, message);
        }

        // In case the current document is a target, also mark the draft as archived
        if (isTarget) {
            DocumentReference draft = getDraftDocument(document, xcontext);
            if (draft != null) {
                return archive(draft, false);
            }
        }
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
        return publishFromArchive(document, true);
    }

    public boolean publishFromArchive(DocumentReference document, boolean isTarget) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument archivedDoc = xcontext.getWiki().getDocument(document, xcontext).clone();
        DocumentReference publisher = xcontext.getUserReference();

        BaseObject archivedWorkflow;
        if (isTarget) {
            archivedWorkflow = validateWorkflow(archivedDoc, Collections.singletonList(STATUS_ARCHIVED), PUBLISHED, xcontext);
        } else {
            archivedWorkflow = archivedDoc.getXObject(PUBLICATION_WORKFLOW_CLASS);
        }

        if (archivedWorkflow == null) {
            return false;
        }

        // Preconditions are met, mark document as published and unhide it
        archivedWorkflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
        
        // Add the author in order to keep track of the person who change the status
        archivedWorkflow.set(WF_STATUS_AUTHOR_FIELDNAME, xcontext.getUserReference().toString(), xcontext);
        
        archivedDoc.setHidden(false);

        // save it
        String defaultMessage = "Published document from an archive by {0}.";
        String message = getMessage("workflow.save.publishFromArchive", defaultMessage,
            Collections.singletonList(stringSerializer.serialize(publisher)));
        saveDocumentWithoutRightsCheck(archivedDoc, message, true, xcontext);

        LOGGER.info("{} {}", message, stringSerializer.serialize(document));

        // If workflow scope includes children, unhide children as well in case the document is a target or
        // if drafts should not be hidden
        if (archivedWorkflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME) == 1) {
            if (isTarget) {
                updateChildrenHiddenStatus(document, false, message);
            } else {
                BaseObject wfConfig =
                    configManager.getWorkflowConfig(archivedWorkflow.getStringValue(WF_CONFIG_REF_FIELDNAME),
                    xcontext);
                int hideDrafts = wfConfig.getIntValue(WF_CONFIG_CLASS_HIDEDRAFT_FIELDNAME, 1);
                if (hideDrafts == 0) {
                    updateChildrenHiddenStatus(document, false, message);
                }
            }
        }

        // In case the current document is a target, also mark the draft as archived
        if (isTarget) {
            DocumentReference draft = getDraftDocument(document, xcontext);
            if (draft != null) {
                return publishFromArchive(draft, false);
            }
        }
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
     * document, the caller is responsible for that, so that they can perform additional operations on the destination
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
            StringBuilder exceptions = new StringBuilder();
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
                    + exceptions);
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
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(GLOBAL_RIGHTS_CLASS,
            document.getDocumentReference()));
        document.removeXObjects(explicitReferenceDocRefResolver.resolve(PUBLICATION_WORKFLOW_CLASS,
            document.getDocumentReference()));
    }

    /**
     * Turns a document in a draft document by setting the appropriate rights, hidden, settings in the workflow object.
     * 
     * @param doc the document to be turned into a draft
     * @param workflow the workflow object associated with the document. if null then the object will be fetched from the document.
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
     * helper method to save the current document despite of the fact that the current user might not have edit rights.
     * this happens on a regular basis every time the document is promoted by one workflow step, which
     * as a side effect removed edit rights from the account doing the promotion.
     * @param doc the document to save
     * @param saveMessage the message for the new version
     * @param isMinorEdit if this is a minor edit
     * @param xcontext the context of the current execution
     * @throws XWikiException if the save fails
     */
    protected void saveDocumentWithoutRightsCheck(XWikiDocument doc, String saveMessage, boolean isMinorEdit,
        XWikiContext xcontext) throws XWikiException
    {
        DocumentReference currentUserReference = xcontext.getUserReference();
        if (currentUserReference != null) {
            doc.setAuthorReference(currentUserReference);
            if (doc.isNew()) {
                doc.setCreatorReference(currentUserReference);
            }
        }

        xcontext.getWiki().saveDocument(doc, saveMessage, isMinorEdit, xcontext);
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
        // trim the message, whichever that is, to 1023 characters, otherwise we're in trouble
        if (message.length() > 1023) {
            // add some dots to show that it was trimmed
            message = message.substring(0, 1020) + "...";
        }
        return message;
    }

    /**
     * Copies/merges a source page to a target in all source page locales. Also copies recursively the source
     * children if {@code includeChildren} is <code>true</code>. The source locales and children get removed from the
     * target if they do not exist in the source. In case the page to be copied is a workflow document (i.e. a document
     * holding a <code>PublicationWorkflowClass</code> object), sets up a workflow object in the target. Fires a
     * {@link DocumentChildPublishingEvent} when copying source pages which are not holding a workflow object.
     * @param source a reference to a document to be copied/merged
     * @param target a reference to the document to be created or updated
     * @param workflowDocumentReference a reference to the workflow document holding the workflow object in the
     * context of which the copy occurs
     * @param publisher a reference to the user performing the action
     * @param includeChildren <code>true</code> if the children of the source should be copied as well, <code>false</code>
     * otherwise
     * @param publicationComment a specific publication comment to be used instead of the default translation key
     * @throws XWikiException in case an error occurs
     */
    public void copyDocument(DocumentReference source, DocumentReference target,
        DocumentReference workflowDocumentReference, DocumentReference publisher, boolean includeChildren,
        String publicationComment) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        XWikiDocument sourceDocument = xcontext.getWiki().getDocument(source, xcontext);
        XWikiDocument targetDocument = xcontext.getWiki().getDocument(target, xcontext);
        final Locale origLocale = xcontext.getLocale();
        List<Locale> locales = sourceDocument.getTranslationLocales(xcontext);
        List<Locale> publishedLocales = targetDocument.getTranslationLocales(xcontext);
        locales.add(0, sourceDocument.getDefaultLocale());

        for (Locale locale : locales) {
            XWikiDocument translatedNewDocument = copyTranslatedDocument(sourceDocument, targetDocument, locale,
                xcontext);

            // Published document is visible.
            // Avoid to make the WebPreferences pages visible.
            if (!WEB_PREFERENCES.equals(source.getName())) {
                translatedNewDocument.setHidden(false);
            }

            boolean isWorkflowDocument = target.equals(workflowDocumentReference);
            if (isWorkflowDocument && locale.equals(sourceDocument.getDefaultLocale())) {
                // set up the workflow and target flag, if a workflow doesn't exist already - only needs to be done for default locale
                BaseObject newWorkflow = targetDocument.getXObject(PUBLICATION_WORKFLOW_CLASS);
                if (newWorkflow == null) {
                    BaseObject sourceWorkflow = sourceDocument.getXObject(PUBLICATION_WORKFLOW_CLASS);
                    newWorkflow = targetDocument.newXObject(PUBLICATION_WORKFLOW_CLASS, xcontext);
                    newWorkflow.set(WF_STATUS_FIELDNAME, STATUS_PUBLISHED, xcontext);
                    newWorkflow.set(WF_INCLUDE_CHILDREN_FIELDNAME,
                        sourceWorkflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME), xcontext);
                    newWorkflow.set(WF_IS_TARGET_FIELDNAME, 1, xcontext);
                    newWorkflow.set(WF_TARGET_FIELDNAME, compactWikiSerializer.serialize(target), xcontext);
                    newWorkflow.set(WF_CONFIG_REF_FIELDNAME, sourceWorkflow.getStringValue(WF_CONFIG_REF_FIELDNAME),
                        xcontext);
                    newWorkflow.set(WF_PUBLICATION_COMMENT_FIELDNAME,
                        sourceWorkflow.getStringValue(WF_PUBLICATION_COMMENT_FIELDNAME), xcontext);
                }
            }

            // TODO: figure out who should be the author of the published document
            // currently it is the user who publishes it (as this one is uniquely determined,
            // unlike the contributor(s), who might be several users)

            // save the published document prepared like this
            try {
                xcontext.setLocale(translatedNewDocument.getRealLocale());
                String message = publicationComment;
                if (message == null) {
                    message = getMessage("workflow.save.publishNew", DEFAULT_PUBLICATION_COMMENT,
                        Collections.singletonList(stringSerializer.serialize(publisher)));
                }

                // set up the context to let events know that they are in the publishing context
                xcontext.put(CONTEXTKEY_PUBLISHING, true);
                if (!isWorkflowDocument) {
                    observationManager.notify(new DocumentChildPublishingEvent(target, workflowDocumentReference),
                        translatedNewDocument, xcontext);
                }
                saveDocumentWithoutRightsCheck(translatedNewDocument, message, false, xcontext);
                LOGGER
                    .debug("{}{}", message,
                        locale.equals(sourceDocument.getDefaultLocale()) ? "" : " (in locale " + locale +
                            ")");
            } finally {
                xcontext.remove(CONTEXTKEY_PUBLISHING);
                xcontext.setLocale(origLocale);
            }
        }
        // remove the languages which are not anymore
        publishedLocales.removeAll(locales);
        for (Locale toRemove : publishedLocales) {
            XWikiDocument translatedDocumentToRemove = targetDocument.getTranslatedDocument(toRemove, xcontext);
            if (translatedDocumentToRemove != targetDocument) {
                xcontext.getWiki().deleteDocument(translatedDocumentToRemove, xcontext);
                LOGGER.debug("deleted published target {} in locale {}", stringSerializer.serialize(target), toRemove);
            }
        }
        // Copy the page children if the "includeChildren" argument is true, and remove the obsolete ones
        if (includeChildren) {
            List<DocumentReference> children = getChildren(source);
            // Retrieve all target's children and keep only the ones that do not have a counterpart in the source
            // anymore so as to delete them.
            List<DocumentReference> obsoletePublishedChildren = getChildren(target);
            for (DocumentReference child : children) {
                DocumentReference childTarget = getChildTarget(child, source, target);
                copyDocument(child, childTarget, workflowDocumentReference, publisher,true, publicationComment);
                obsoletePublishedChildren.remove(childTarget);
            }
            // Delete target's children without a counterpart in the source
            for (DocumentReference obsoletePublishedChild : obsoletePublishedChildren) {
                XWikiDocument obsoletePublishedChildDocument =
                    xcontext.getWiki().getDocument(obsoletePublishedChild, xcontext);
                xcontext.getWiki().deleteDocument(obsoletePublishedChildDocument, xcontext);
            }
        }
    }

    /**
     * Returns the children references of a given document reference based on the reference hierarchy.
     * @param reference a {@link DocumentReference}
     * @return child pages references
     */
    protected List<DocumentReference> getChildren(DocumentReference reference)
    {
        String name = entityTreeNodeIdConverter.convert(String.class, reference);
        int childrenCount = tree.getChildCount(name);
        List<String> children = tree.getChildren(name, 0, childrenCount);
        List<DocumentReference> childReferences = new ArrayList<>();
        for (String childNodeId : children) {
            // FIXME: do not use hardcoded prefix "document:"
            String childName = childNodeId.replace("document:", "");
            DocumentReference childReference = explicitStringDocRefResolver.resolve(childName, reference);
            childReferences.add(childReference);
        }
        return childReferences;
    }

    @Override
    public DocumentReference getChildTarget(DocumentReference child, DocumentReference workflowDocumentDraft,
        DocumentReference workflowDocumentTarget)
    {
        return child.replaceParent(workflowDocumentDraft.getParent(), workflowDocumentTarget.getParent());
    }

    @Override
    public DocumentReference getWorkflowDocument(DocumentReference document) throws XWikiException
    {
        List<EntityReference> chain = document.getReversedReferenceChain();
        XWikiContext context = getXContext();
        for (int i = chain.size(); i-- > 0; ) {
            EntityReference ancestor = chain.get(i);
            if (ancestor.getType() == EntityType.DOCUMENT || ancestor.getType() == EntityType.SPACE) {
                XWikiDocument ancestorDocument = context.getWiki().getDocument(ancestor, context);
                BaseObject workflow = ancestorDocument.getXObject(PUBLICATION_WORKFLOW_CLASS);
                if (workflow != null) {
                    // Return ancestor reference either if it is the passed document reference itself
                    // or if it is a real ancestor with a workflow whose scope includes the descendants.
                    int includeChildren = workflow.getIntValue(WF_INCLUDE_CHILDREN_FIELDNAME);
                    if (ancestorDocument.getDocumentReference().equals(document) || includeChildren == 1) {
                        return ancestorDocument.getDocumentReference();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Returns <code>true</code> if the passed reference is terminal, <code>false</code> otherwise.
     * @param reference a {@link DocumentReference}
     * @return <code>true</code> if the passed reference is terminal, <code>false</code> otherwise
     */
    protected boolean isTerminal(DocumentReference reference)
    {
        String defaultDocumentName =
            this.defaultEntityReferenceProvider.getDefaultReference(EntityType.DOCUMENT).getName();
        return !reference.getName().equals(defaultDocumentName);
    }

    /**
     * Persists a set of rules in the document holding the rights for a given document, and saves these rules only if
     * the document holding the rights differs from the passed document. In case of terminal pages, the document holding
     * the rights is the same as the workflow document, the rules get persisted to objects without performing a save
     * since the save is performed separately. In case of non-terminal pages whose workflow scope includes the children,
     * the document holding the rights is <code>WebPreferences</code>, the rules are persisted to objects and saved.
     *
     * @param doc an XWikiDocument which holds a workflow object
     * @param rules a list of {@link org.xwiki.security.authorization.SecurityRule}
     * @param includeChildren whether the document's workflow scope includes children or not
     * @throws XWikiException in case an error occurs when saving the document holding the rights
     */
    protected void persistAndMaybeSaveRules(XWikiDocument doc, List<ReadableSecurityRule> rules, boolean includeChildren)
        throws XWikiException
    {
        if (includeChildren && !isTerminal(doc.getDocumentReference())) {
            SpaceReference spaceReference = doc.getDocumentReference().getLastSpaceReference();
            rightsWriter.saveRules(rules, spaceReference);
        } else {
            rulesObjectWriter.persistRulesToObjects(rules, doc, RIGHTS_CLASS, getXContext());
        }
    }

    /**
     * Converts a list of document full names to a list of {@link DocumentReference}, filtering out empty names.
     * @param list a list of document full names
     * @param relativeTo a relative reference to be used when resolving strings to {@link DocumentReference}
     * @return a list of {@link DocumentReference}
     */
    protected List<DocumentReference> toDocumentReferenceList(List<String> list, DocumentReference relativeTo)
    {
        return list.stream().filter(group -> !group.trim().isEmpty()).map(group -> explicitStringDocRefResolver.resolve(group
            , relativeTo)).collect(Collectors.toList());
    }

    /**
     * Hides/unhides recursively the children of a given {@link DocumentReference}.
     * @param reference a {@link DocumentReference}
     * @param hidden whether the page should be hidden or unhidden
     * @param message the version comment to be stored in the page history
     * @throws XWikiException in case an error occurs
     */
    protected void updateChildrenHiddenStatus(DocumentReference reference, boolean hidden,
        String message) throws XWikiException
    {
        XWikiContext xcontext = getXContext();
        for (DocumentReference child: getChildren(reference)) {
            // Avoid to make the WebPreferences pages visible.
            if (WEB_PREFERENCES.equals(child.getName())) {
                return;
            }
            XWikiDocument childDocument = xcontext.getWiki().getDocument(child, xcontext).clone();
            if (childDocument.isHidden() != hidden) {
                childDocument.setHidden(hidden);
                // The message is empty when the method is called from setupDraftAccess, which can happen in multiple
                // contexts which carry their own message, currently not passed as an argument.
                if (StringUtils.isEmpty(message)) {
                    if (hidden) {
                        message = getMessage("workflow.save.hide", "Mark as hidden", null);
                    } else {
                        message = getMessage("workflow.save.unhide", "Mark as unhidden", null);
                    }
                }
                // XWikiDocument#setHidden does not flag the document metadata as dirty so we need to flag it so that
                // the document gets really saved in the database.
                childDocument.setMetaDataDirty(true);
                saveDocumentWithoutRightsCheck(childDocument, message, true, xcontext);
                LOGGER.info("{} {}", message, stringSerializer.serialize(child));
                updateChildrenHiddenStatus(child, hidden, message);
            }
        }
    }
}
