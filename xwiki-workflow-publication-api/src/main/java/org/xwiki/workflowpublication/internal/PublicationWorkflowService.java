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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.script.service.ScriptService;
import org.xwiki.security.authorization.AuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.PublicationWorkflow;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * @version $Id$
 */
@Component
@Named("publicationworkflow")
@Singleton
public class PublicationWorkflowService implements ScriptService
{
    @Inject
    protected PublicationWorkflow publicationWorkflow;

    @Inject
    protected PublicationRoles publicationRoles;

    /**
     * The execution, to get the context from it.
     */
    @Inject
    protected Execution execution;

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    /**
     * Reference string serializer.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;

    /**
     * Reference resolver for string representations of references.
     */
    @Inject
    @Named("current")
    protected DocumentReferenceResolver<String> referenceResolver;

    @Inject
    private AuthorizationManager authManager;

    public boolean isWorkflowDocument(String document)
    {
        try {
            // resolve userReference
            XWikiContext context = getXContext();
            // resolve document reference if any is specified
            XWikiDocument documentObject = null;
            if (!StringUtils.isEmpty(document)) {
                DocumentReference documentRef = referenceResolver.resolve(document);
                documentObject = context.getWiki().getDocument(documentRef, context);
            }

            return this.publicationWorkflow.isWorkflowDocument(documentObject, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting the workflow for document {}", document, e);
            return false;
        }
    }

    public boolean isModified(DocumentReference fromDoc, DocumentReference toDoc)
    {
        XWikiContext xcontext = getXContext();
        try {
            XWikiDocument fromDocDoc = xcontext.getWiki().getDocument(fromDoc, xcontext);
            XWikiDocument toDocDoc = xcontext.getWiki().getDocument(toDoc, xcontext);
            return this.publicationWorkflow.isModified(fromDocDoc, toDocDoc, xcontext);
        } catch (XWikiException e) {
            logger.warn("Could not compare documents {} and {}", stringSerializer.serialize(fromDoc),
                stringSerializer.serialize(toDoc), e);
            // by default, if we don't know better, we say they are different
            return true;
        }
    }

    public boolean startWorkflow(DocumentReference doc, String workflowConfig, DocumentReference target)
    {
        return this.startWorkflow(doc, false, workflowConfig, target);
    }

    public boolean startWorkflow(DocumentReference doc, boolean includeChildren, String workflowConfig,
        DocumentReference target)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (authManager.hasAccess(Right.EDIT, xcontext.getUserReference(), doc)) {
                return this.publicationWorkflow.startWorkflow(doc, includeChildren, workflowConfig, target, xcontext);
            } else {
                // TODO: put error on context
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not start workflow for document {} and config {}", stringSerializer.serialize(doc),
                workflowConfig);
            // TODO: put error on context
            return false;
        }
    }

    /**
     * Starts the workflow on {@code target} as the published document, without creating the draft document. The draft
     * can be created the first time when the function {@link #createDraftDocument(DocumentReference)} will be called on
     * this published document. Roughly this function is only setting up the marker on {@code target} as a published
     * documemt. It does, however, all verifications (that there is no other workflow on that document, etc.).
     *
     * @param docRef the document reference on which the workflow will be started
     * @param workflowConfig the configuration information for the workflow
     * @return {@code true} if the workflow was successfully started on the specified target {@code false} otherwise
     * @deprecated use {@link #startWorkflowAsTarget(DocumentReference, boolean, String)}
     */
    public boolean startWorkflowAsTarget(DocumentReference docRef, String workflowConfig)
    {
        return startWorkflowAsTarget(docRef, true, workflowConfig);
    }

    /**
     * Starts the workflow on {@code target} as the published document, without creating the draft document. The draft
     * can be created the first time when the function {@link #createDraftDocument(DocumentReference)} will be called on
     * this published document. Roughly this function is only setting up the marker on {@code target} as a published
     * documemt. It does, however, all verifications (that there is no other workflow on that document, etc.).
     *
     * @param docRef the document reference on which the workflow will be started
     * @param includeChildren {@code true} if the workflow should include child documents {@code false} otherwise
     * @param workflowConfig the configuration information for the workflow
     * @return {@code true} if the workflow was successfully started on the specified target {@code false} otherwise
     * @since 2.4
     */
    public boolean startWorkflowAsTarget(DocumentReference docRef, boolean includeChildren, String workflowConfig)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (authManager.hasAccess(Right.EDIT, xcontext.getUserReference(), docRef)) {
                return this.publicationWorkflow.startWorkflowAsTarget(docRef, includeChildren, workflowConfig,
                    xcontext);
            } else {
                // TODO: put error on context
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not start workflow for document {} and config {}.", stringSerializer.serialize(docRef),
                workflowConfig);
            // TODO: put error on context
            return false;
        }
    }

    public DocumentReference getDraftDocument(DocumentReference target)
    {
        XWikiContext xcontext = getXContext();
        return this.publicationWorkflow.getDraftDocument(target, xcontext);
    }

    public DocumentReference getDraftDocument(DocumentReference target, String wiki)
    {
        XWikiContext xcontext = getXContext();
        return this.publicationWorkflow.getDraftDocument(target, wiki, xcontext);
    }

    /**
     * Creates a draft document corresponding to the passed target reference, which will have as a target the passed
     * reference. The draft document is created in the same wiki, the space where the document is created is taken from
     * the defaultDraftsSpace property of the workflow config of the target and the name of the draft document is a
     * unique name generated starting from the target document.
     *
     * @param targetRef the reference to the target document for which a draft is being created
     * @return {@code true} if the draft document was successfully created {@code false} otherwise
     */
    public DocumentReference createDraftDocument(DocumentReference targetRef)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(targetRef, xcontext), xcontext))
            {
                return this.publicationWorkflow.createDraftDocument(targetRef, xcontext);
            } else {
                return null;
            }
        } catch (XWikiException e) {
            logger.warn("Could not create draft for target {}", stringSerializer.serialize(targetRef));
            // TODO: put error on context
            return null;
        }
    }

    public boolean submitForModeration(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.submitForModeration(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not submit document {} to moderation", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public boolean refuseModeration(DocumentReference document, String reason)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canModerate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.refuseModeration(document, reason);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not refuse moderation for document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public boolean submitForValidation(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canModerate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.submitForValidation(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not submit document {} to validation", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public boolean refuseValidation(DocumentReference document, String reason)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canValidate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.refuseValidation(document, reason);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not refuse validation for document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public boolean validate(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canValidate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.validate(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not mark document {} as valid.", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public DocumentReference publish(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canValidate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.publish(document);
            } else {
                return null;
            }
        } catch (XWikiException e) {
            logger.warn("Could not publish document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return null;
        }
    }

    public DocumentReference unpublish(DocumentReference document, boolean forceToDraft)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.unpublish(document, forceToDraft);
            } else {
                return null;
            }
        } catch (XWikiException e) {
            logger.warn("Could not unpublish document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return null;
        }
    }
    
    public boolean editDraft(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.editDraft(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not change {} status to draft", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public boolean archive(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.archive(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not archive document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    public DocumentReference unarchive(DocumentReference document, boolean forceToDraft)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.unarchive(document, forceToDraft);
            } else {
                return null;
            }
        } catch (XWikiException e) {
            logger.warn("Could not unpublish document {}", stringSerializer.serialize(document));
            // TODO: put error on context
            return null;
        }
    }

    public boolean publishFromArchive(DocumentReference document)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canValidate(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(document, xcontext), xcontext)) {
                return this.publicationWorkflow.publishFromArchive(document);
            } else {
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not publish document {} from archive", stringSerializer.serialize(document));
            // TODO: put error on context
            return false;
        }
    }

    /**
     * See {@link PublicationWorkflow#getChildTarget(DocumentReference, DocumentReference, DocumentReference)}
     * @param reference a reference to a workflow document child
     * @param workflowTarget the target of the workflow document owning the given child
     * @return a reference to the child target
     */
    public DocumentReference getChildTarget(DocumentReference reference, DocumentReference workflowDraft,
        DocumentReference workflowTarget)
    {
        return this.publicationWorkflow.getChildTarget(reference, workflowDraft, workflowTarget);
    }

    /**
     * @return the xwiki context from the execution context
     */
    private XWikiContext getXContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }
}
