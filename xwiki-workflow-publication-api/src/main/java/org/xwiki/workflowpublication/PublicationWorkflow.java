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
package org.xwiki.workflowpublication;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.stability.Unstable;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * @version $Id$
 */
@Role
public interface PublicationWorkflow
{
    /**
     * The reference to the workflow class, relative to the current wiki.
     */
    EntityReference PUBLICATION_WORKFLOW_CLASS = new EntityReference("PublicationWorkflowClass",
        EntityType.DOCUMENT, new EntityReference("PublicationWorkflow", EntityType.SPACE));

    /**
     * The reference to the workflow config class, relative to the current wiki.
     */
    EntityReference PUBLICATION_WORKFLOW_CONFIG_CLASS = new EntityReference("PublicationWorkflowConfigClass",
        EntityType.DOCUMENT, new EntityReference("PublicationWorkflow", EntityType.SPACE));

    boolean isWorkflowDocument(XWikiDocument document, XWikiContext context);

    /**
     * Returns {@code true} if the two documents are different, {@code false} otherwise. Note that this function ignores
     * changes in comments, rights and publication workflow objects, as well as metadata changes such as dates and
     * authors. It is made to compare a published document with a draft document to decide whether one needs to be
     * overwritten with the other (on unpublish).
     * 
     * @param fromDoc
     * @param toDoc
     * @param context
     * @return
     * @throws XWikiException
     */
    boolean isModified(XWikiDocument fromDoc, XWikiDocument toDoc, XWikiContext context) throws XWikiException;

    boolean startWorkflow(DocumentReference doc, String workflowConfig, DocumentReference target,
        XWikiContext xcontext) throws XWikiException;

    @Unstable
    boolean startWorkflow(DocumentReference doc, boolean includeChildren, String workflowConfig,
        DocumentReference target, XWikiContext xcontext) throws XWikiException;

    /**
     * Starts the workflow on {@code target} as the published document, without creating the draft document. The draft
     * can be created the first time when the function {@link #createDraftDocument(DocumentReference, XWikiContext)}
     * will be called on this published document. Roughly this function is only setting up the marker on {@code target}
     * as a published documemt. It does, however, all verifications (that there is no other workflow on that document,
     * etc.).
     *
     * @param target the document reference on which the workflow will be started
     * @param workflowConfig the configuration information for the workflow
     * @param xcontext the context information
     * @return {@code true} if the workflow was successfully started on the specified target {@code false} otherwise
     * @throws XWikiException if an exception occurs during the workflow process
     * @deprecated use {@link #startWorkflowAsTarget(DocumentReference, boolean, String, XWikiContext)}
     */
    @Deprecated
    boolean startWorkflowAsTarget(DocumentReference target, String workflowConfig, XWikiContext xcontext)
        throws XWikiException;

    /**
     * Starts the workflow on {@code target} as the published document, without creating the draft document. The draft
     * can be created the first time when the function {@link #createDraftDocument(DocumentReference, XWikiContext)}
     * will be called on this published document. Roughly this function is only setting up the marker on {@code target}
     * as a published documemt. It does, however, all verifications (that there is no other workflow on that document,
     * etc.).
     *
     * @param target the document reference on which the workflow will be started
     * @param includeChildren {@code true} if the workflow should include child documents {@code false} otherwise
     * @param workflowConfig the configuration information for the workflow
     * @param xcontext the context information
     * @return {@code true} if the workflow was successfully started on the specified target {@code false} otherwise
     * @throws XWikiException if an exception occurs during the workflow process
     *
     * @since 2.4
     */
    boolean startWorkflowAsTarget(DocumentReference target, boolean includeChildren, String workflowConfig,
        XWikiContext xcontext) throws XWikiException;

    /**
     * Gets the draft document corresponding to the passed target: meaning the workflow document which is not a target
     * and which has the passed target as target. Note that this function looks for the draft document on the same wiki
     * as the passed target document.
     * 
     * @param targetRef
     * @param xcontext
     * @return
     * @throws XWikiException
     */
    DocumentReference getDraftDocument(DocumentReference targetRef, XWikiContext xcontext) throws XWikiException;

    /**
     * Gets the draft document corresponding to the passed target: meaning the workflow document which is not a target
     * and which has the passed target as target. Note that this function looks for the document in the passed wiki. The
     * target is compared to the target relative to the passed wiki: without wiki reference if the target is in the same
     * wiki as the passed wiki and fully prefixed if they're in different wikis. This function should be called when,
     * for a reason or another, the draft is not in the same wiki as the target document.
     * 
     * @param wiki parameter should be the wiki where the draft is expected to be.
     * @param targetRef
     * @param wiki
     * @param xcontext
     * @return
     * @throws XWikiException
     * @since 1.1
     */
    DocumentReference getDraftDocument(DocumentReference targetRef, String wiki, XWikiContext xcontext)
        throws XWikiException;

    /**
     * Creates a draft document corresponding to the passed target reference, which will have as a target the passed
     * reference. The draft document is created in the same wiki, the space where the document is created is taken from
     * the defaultDraftsSpace property of the workflow config of the target and the name of the draft document is a
     * unique name generated starting from the target document.
     * 
     * @param targetRef the reference to the target document for which a draft is being created
     * @param xcontext the context information
     * @return {@code true} if the draft document was successfully created {@code false} otherwise
     * @throws XWikiException if an exceptions occurs during the workflow process
     */
    DocumentReference createDraftDocument(DocumentReference targetRef, XWikiContext xcontext)
        throws XWikiException;

    /**
     * Sets up the draft rigths and visibility on the passed XWikiDocument, as a draft document. It's a helper function
     * for the listeners to be able to set up the rights with one function. Workflow groups configuration will be taken
     * from the workflow object that is attached to the passed document. If such workflow config does not exist, the
     * document will not be changed. Note that this function does not save the document, it just changes it.
     * 
     * @param document The document to set the draft access settings on
     * @param xcontext the context of the modification
     * @throws XWikiException in case something goes wrong
     */
    void setupDraftAccess(XWikiDocument document, XWikiContext xcontext) throws XWikiException;

    /* Functions to be used from the scripts */

    /**
     * draft -&gt; contributing + moderator gets explicit rights for edit and contributor does not have anymore. <br>
     * If there are no defined moderators, this will delegate to submitForValidation.
     * 
     * @param document is the draft document which needs to pass in moderating state
     * @throws XWikiException
     */
    boolean submitForModeration(DocumentReference document) throws XWikiException;

    /**
     * moderating -&gt; draft + contributor gets edit rights back
     * 
     * @param document
     * @param reason
     * @return
     * @throws XWikiException
     */
    boolean refuseModeration(DocumentReference document, String reason) throws XWikiException;

    /**
     * moderating -&gt; validating. + moderator looses edit rights
     * 
     * @param document
     * @return
     * @throws XWikiException
     */
    boolean submitForValidation(DocumentReference document) throws XWikiException;

    /**
     * validating -&gt; draft. + contributor and moderator get back rights
     * 
     * @param documnet
     * @param reason
     * @return
     * @throws XWikiException
     */
    boolean refuseValidation(DocumentReference documnet, String reason) throws XWikiException;

    /**
     * validating -&gt; validated. Rights stay the same as in validating state. This extra state is needed in order to be
     * able to delay the effective publishing of the document (making it available to users as a published document).
     * 
     * @param document
     * @return
     * @throws XWikiException
     */
    boolean validate(DocumentReference document) throws XWikiException;

    /**
     * validated or validating -&gt; published + document gets copied in its final place where it will be readonly anyway
     * 
     * @param document
     * @return
     * @throws XWikiException
     */
    DocumentReference publish(DocumentReference document) throws XWikiException;

    /**
     * published -&gt; draft. Published document gets deleted. Content from the published document can be copied to the
     * draft or just ignored, depending on the parameter.
     * 
     * @param document the published document that should be turned to draft.
     * @param forceToDraft
     * @return the draft document
     * @throws XWikiException
     */
    DocumentReference unpublish(DocumentReference document, boolean forceToDraft) throws XWikiException;

    /**
     * To edit back a draft whose status is published
     * 
     * @param document The draft that we want to edit
     * @return
     * @throws XWikiException
     */
    boolean editDraft(DocumentReference document) throws XWikiException;

    /**
     * published -&gt; archived. Not yet sure how it would work.
     * 
     * @param document the published document that should be archived.
     * @return
     * @throws XWikiException
     */
    boolean archive(DocumentReference document) throws XWikiException;

    /**
     * archived -&gt; draft. Archived document gets deleted. Content from the archived document can be copied to the draft
     * or just ignored, depending on the parameter.
     * 
     * @param document the archived document that should get drafted.
     * @return
     * @throws XWikiException
     */
    DocumentReference unarchive(DocumentReference document, boolean forceToDraft) throws XWikiException;

    /**
     * archived -&gt; published. Not yet sure how it would work.
     * 
     * @param document the archived document that should go back to being published.
     * @return
     * @throws XWikiException
     */
    boolean publishFromArchive(DocumentReference document) throws XWikiException;

    /**
     * Returns a reference to the first document containing a workflow object in the given reference's ancestors
     * including the passed reference itself, from the passed reference to the root, and whose hierarchical scope
     * ("includeChildren" option) includes the passed document in case the workflow document is an ancestor.
     * @param reference a {@link DocumentReference}
     * @return a reference to the ancestor workflow owning the passed reference if any, null otherwise
     * @throws XWikiException
     */
    @Unstable
    DocumentReference getWorkflowDocument(DocumentReference reference) throws XWikiException;

    /**
     * Computes the target of a workflow document descendant, based on the workflow document target. For instance, if
     * the workflow document is "Drafts.ABC.WebHome", with a target "Published.ABC.WebHome", the target of the
     * descendant document "Drafts.ABC.DEF.WebHome" is "Published.ABC.DEF.WebHome".
     * @param descendant a reference to a descendant of a workflow document
     * @param workflowDocumentTarget the target of the workflow document owning the given descendant
     * @return the descendant's target reference
     */
    @Unstable
    DocumentReference getChildTarget(DocumentReference descendant, DocumentReference workflowDocumentDraft,
        DocumentReference workflowDocumentTarget);
}
