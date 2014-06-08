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

    public boolean isWorkflowDocument(String document)
    {
        try {
            // resolve userReference
            XWikiContext context = getXContext();
            // resolve document reference if any is specified
            XWikiDocument documentObject = null;
            if (!StringUtils.isEmpty(document)) {
                DocumentReference documentRef = (DocumentReference) referenceResolver.resolve(document);
                documentObject = context.getWiki().getDocument(documentRef, context);
            }

            return this.publicationWorkflow.isWorkflowDocument(documentObject, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting the workflow for document " + document, e);
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
            logger.warn("Could not compare documents " + stringSerializer.serialize(fromDoc) + " and "
                + stringSerializer.serialize(toDoc), e);
            // by default, if we don't know better, we say they are different
            return true;
        }
    }

    public boolean startWorkflow(DocumentReference doc, String workflowConfig, DocumentReference target)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (xcontext
                .getWiki()
                .getRightService()
                .hasAccessLevel("edit", stringSerializer.serialize(xcontext.getUserReference()),
                    stringSerializer.serialize(doc), xcontext)) {
                return this.publicationWorkflow.startWorkflow(doc, workflowConfig, target, xcontext);
            } else {
                // TODO: put error on context
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not start workflow for document " + stringSerializer.serialize(doc) + " and config "
                + workflowConfig);
            // TODO: put error on context
            return false;
        }
    }
    
    public boolean startWorkflowAsTarget(DocumentReference doc, String workflowConfig)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (xcontext
                .getWiki()
                .getRightService()
                .hasAccessLevel("edit", stringSerializer.serialize(xcontext.getUserReference()),
                    stringSerializer.serialize(doc), xcontext)) {
                return this.publicationWorkflow.startWorkflowAsTarget(doc, workflowConfig, xcontext);
            } else {
                // TODO: put error on context
                return false;
            }
        } catch (XWikiException e) {
            logger.warn("Could not start workflow for document " + stringSerializer.serialize(doc) + " and config "
                + workflowConfig);
            // TODO: put error on context
            return false;
        }
    }    

    public DocumentReference getDraftDocument(DocumentReference target)
    {
        XWikiContext xcontext = getXContext();
        try {
            return this.publicationWorkflow.getDraftDocument(target, xcontext);
        } catch (XWikiException e) {
            logger.warn("Could not query for workflow draft for target " + stringSerializer.serialize(target));
            // TODO: put error on context
            return null;
        }
    }

    public DocumentReference getDraftDocument(DocumentReference target, String wiki)
    {
        XWikiContext xcontext = getXContext();
        try {
            return this.publicationWorkflow.getDraftDocument(target, wiki, xcontext);
        } catch (XWikiException e) {
            logger.warn("Could not query for workflow draft for target " + stringSerializer.serialize(target)
                + " on wiki " + wiki);
            // TODO: put error on context
            return null;
        }
    }

    public DocumentReference createDraftDocument(DocumentReference target)
    {
        XWikiContext xcontext = getXContext();
        try {
            if (this.publicationRoles.canContribute(xcontext.getUserReference(),
                xcontext.getWiki().getDocument(target, xcontext), xcontext)) {
                return this.publicationWorkflow.createDraftDocument(target, xcontext);
            } else {
                return null;
            }
        } catch (XWikiException e) {
            logger.warn("Could not create draft for target " + stringSerializer.serialize(target));
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
            logger.warn("Could not submit document " + stringSerializer.serialize(document) + " to moderation");
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
            logger.warn("Could not refuse moderation for document " + stringSerializer.serialize(document));
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
            logger.warn("Could not submit document " + stringSerializer.serialize(document) + " to validation");
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
            logger.warn("Could not refuse validation for document " + stringSerializer.serialize(document));
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
            logger.warn("Could not mark document " + stringSerializer.serialize(document) + " as valid.");
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
            logger.warn("Could not publish document " + stringSerializer.serialize(document));
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
            logger.warn("Could not unpublish document " + stringSerializer.serialize(document));
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
            logger.warn("Could not change " + stringSerializer.serialize(document) + " status to draft");
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
            logger.warn("Could not archive document " + stringSerializer.serialize(document));
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
            logger.warn("Could not unpublish document " + stringSerializer.serialize(document));
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
            logger.warn("Could not publish document " + stringSerializer.serialize(document) + " from archive");
            // TODO: put error on context
            return false;
        }
    }

    /**
     * @return the xwiki context from the execution context
     */
    private XWikiContext getXContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }
}
