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
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.model.reference.AttachmentReferenceResolver;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.EventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.ImageBlock;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.block.match.ClassBlockMatcher;
import org.xwiki.rendering.listener.reference.ResourceReference;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.workflowpublication.DocumentPublishingEvent;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Event listener to update references in a published document to the published variant. This class listens to document
 * publishing events and tries to find all references in the published document that point to a draft document and
 * update the reference to the published version, if possible
 *
 * @version $Id$
 */
@Component
@Named("published-workflow-document-reference-transformer")
@Singleton
public class ReferencesTransformDocPublishingEventListener implements EventListener
{
    public final static String STATUS_PROPNAME = "status";

    public final static String STATUS_MODERATING = "moderating";

    public final static String STATUS_VALIDATING = "validating";

    public final static String STATUS_DRAFT = "draft";

    public final static String STATUS_PUBLISHED = "published";

    public final static String STATUS_ARCHIVED = "archived";

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
    private DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;

    /**
     * explicit resolvers to get referenced documents and the workflow class
     */
    @Inject
    @Named("explicit")
    private DocumentReferenceResolver<String> explicitStringDocRefResolver;

    @Inject
    @Named("explicit")
    private DocumentReferenceResolver<EntityReference> explicitReferenceDocRefResolver;

    /**
     * explicit resolver to get referenced attachments
     */
    @Inject
    @Named("explicit")
    private AttachmentReferenceResolver<String> explicitStringAttachmentRefResolver;

    /**
     * Reference string serializer.
     */
    @Inject
    private EntityReferenceSerializer<String> stringSerializer;

    @Inject
    private PublicationWorkflow publicationWorkflow;

    /**
     * The events observed by this observation manager.
     */
    private final List<Event> eventsList = new ArrayList<Event>(Arrays.asList(new DocumentPublishingEvent()));

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
        return "published-workflow-document-reference-transformer";
    }

    /**
     * Does the actual work of transforming references in the published document.
     *
     * @see org.xwiki.observation.EventListener#onEvent(org.xwiki.observation.event.Event, java.lang.Object,
     *      java.lang.Object)
     */
    public void onEvent(Event event, Object source, Object data)
    {
        final XWikiDocument publishedDocument = (XWikiDocument) source;

        logger.debug("published {}; check references", publishedDocument);

        final XWikiContext context = (XWikiContext) data;

        try {
            transformReferences(publishedDocument, context);
        } catch (XWikiException e) {
            logger.error("failed to transform references on published document " + publishedDocument, e);
        }

    }

    private void transformReferences(final XWikiDocument publishedDocument, final XWikiContext context)
        throws XWikiException
    {
        final BaseObject workflowInstance = getWorkflowObject(publishedDocument);

        if (workflowInstance == null || workflowInstance
            .getIntValue(DefaultPublicationWorkflow.WF_IS_TARGET_FIELDNAME) != DefaultPublicationWorkflow.PUBLISHED) {
            logger.warn("got published event for document {} which is not a workflow document?", publishedDocument);
            return;
        }

        DocumentReference draftDocumentRef = publicationWorkflow
            .getDraftDocument(publishedDocument.getDocumentReference(), context);

        XDOM xDom = publishedDocument.getXDOM();
        for (Block link : xDom.getBlocks(new ClassBlockMatcher(LinkBlock.class), Block.Axes.DESCENDANT_OR_SELF)) {
            LinkBlock linkBlock = (LinkBlock) link;

            ResourceReference linkTarget = linkBlock.getReference();

            ResourceType type = linkTarget.getType();
            if (ResourceType.DOCUMENT.equals(type)) {
                transformDocumentReference(linkTarget, draftDocumentRef, publishedDocument, context);
            } else if (ResourceType.ATTACHMENT.equals(type)) {
                transformAttachmentReference(linkTarget, draftDocumentRef, publishedDocument, context);
            }

        }

        for (Block image : xDom.getBlocks(new ClassBlockMatcher(ImageBlock.class), Block.Axes.DESCENDANT_OR_SELF)) {
            ImageBlock imageBlock = (ImageBlock) image;

            if (imageBlock.isFreeStandingURI()) {
                continue;
            }

            ResourceReference imageRef = imageBlock.getReference();
            logger.debug("found image reference {}", imageRef);
            transformAttachmentReference(imageRef, draftDocumentRef, publishedDocument, context);
        }

        publishedDocument.setContent(xDom);
        logger.debug("done with {}", publishedDocument);

    }

    //
    // hide the nasty details in some helper methods
    //

    /**
     * small helper to get the workflow object attached to this document.
     *
     * @return the workflow object, or null if no workflow object attached to this class
     */
    private BaseObject getWorkflowObject(final XWikiDocument document)
    {
        return document.getXObject(explicitReferenceDocRefResolver
            .resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS, document.getDocumentReference()));
    }

    /**
     * check if the given document is a draft document in a workflow, and if so, return a reference to its target
     * document, null otherwise.
     *
     * @param document
     *            the document
     * @return null if not in workflow, or the reference to the target document
     */
    private String getTargetDocRefInWorkflow(XWikiDocument document)
    {
        BaseObject workFlow = getWorkflowObject(document);
        if (workFlow == null) {
            logger.debug("target is not in a workflow: {}", document);
            return null;
        }
        if (workFlow
            .getIntValue(DefaultPublicationWorkflow.WF_IS_TARGET_FIELDNAME) != DefaultPublicationWorkflow.DRAFT) {
            logger.debug("target is no draft: {}", document);
            return null;
        }
        return workFlow.getStringValue(DefaultPublicationWorkflow.WF_TARGET_FIELDNAME);
    }

    /**
     * transform the given linkTarget to point to the published version of its target.
     *
     * @param linkTarget
     *            the target of the link; its reference will be modified by the method
     * @param draftDocumentRef
     *            a reference to the draft document containing the reference
     * @param publishedDocument
     *            the published version of the document containing the reference
     * @param context
     *            the current execution context
     * @throws XWikiException
     */
    private void transformDocumentReference(final ResourceReference linkTarget,
        final DocumentReference draftDocumentRef, final XWikiDocument publishedDocument, final XWikiContext context)
        throws XWikiException
    {
        DocumentReference currentLinkReference = explicitStringDocRefResolver.resolve(linkTarget.getReference(),
            draftDocumentRef);
        XWikiDocument currentLinkReferenceDoc = context.getWiki().getDocument(currentLinkReference, context);

        // if we point to a draft object: look up the target:
        String targetDocRef = getTargetDocRefInWorkflow(currentLinkReferenceDoc);
        if (targetDocRef == null) {
            return;
        }

        logger.debug("transform link {} in doc {} to {}", linkTarget.getReference(), publishedDocument, targetDocRef);
        linkTarget.setReference(targetDocRef);
    }

    /**
     * transform the given attTarget pointing to an attachment to point to the published version of its target.
     *
     * @param attTarget
     *            the target of the link; its reference will be modified by the method
     * @param draftDocumentRef
     *            a reference to the draft document containing the reference
     * @param publishedDocument
     *            the published version of the document containing the reference
     * @param context
     *            the current execution context
     * @throws XWikiException
     */
    private void transformAttachmentReference(final ResourceReference attTarget,
        final DocumentReference draftDocumentRef, final XWikiDocument publishedDocument, final XWikiContext context)
        throws XWikiException
    {
        AttachmentReference currentAttachmentLinkReference = explicitStringAttachmentRefResolver
            .resolve(attTarget.getReference(), draftDocumentRef);
        XWikiDocument currentDocumentForAttachment = context.getWiki()
            .getDocument(currentAttachmentLinkReference.getDocumentReference(), context);

        // we point to a draft object: look up the target:
        String targetDocRef = getTargetDocRefInWorkflow(currentDocumentForAttachment);
        if (targetDocRef == null) {
            return;
        }

        DocumentReference targetDocumentReference = explicitStringDocRefResolver.resolve(targetDocRef,
            draftDocumentRef);
        AttachmentReference targetAttachmentReference = new AttachmentReference(
            currentAttachmentLinkReference.getName(), targetDocumentReference);

        String targetAttachmentRef = stringSerializer.serialize(targetAttachmentReference);
        logger.debug("transform att link {} in doc {} to {}", attTarget.getReference(), publishedDocument,
            targetAttachmentRef);
        attTarget.setReference(targetAttachmentRef);
    }
}
