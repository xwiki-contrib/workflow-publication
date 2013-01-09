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

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;

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
    public static final EntityReference PUBLICATION_WORKFLOW_CLASS = new EntityReference("PublicationWorkflowClass",
        EntityType.DOCUMENT, new EntityReference("PublicationWorkflow", EntityType.SPACE));

    boolean isWorkflowDocument(XWikiDocument document, XWikiContext context) throws XWikiException;

    public boolean startWorkflow(DocumentReference doc, String workflowConfig, DocumentReference target,
        XWikiContext xcontext) throws XWikiException;

    public List<String> startMatchingWorkflows(DocumentReference doc, DocumentReference target, XWikiContext xcontext);

    /**
     * Gets the draft document corresponding to the passed target: meaning the workflow document which is not a target
     * and which has the passed target as target. Note that this function looks for the document in the current wiki.
     * The target is compared to the target as relative to the current wiki, if the target is in the current wiki, and
     * absolute if the target is not in the current wiki. So basically this function should be called in the context of
     * the wiki containing the draft.
     * 
     * @param targetRef
     * @param xcontext
     * @return
     * @throws XWikiException
     */
    public DocumentReference getDraftDocument(DocumentReference targetRef, XWikiContext xcontext) throws XWikiException;

    /* Functions to be used from the scripts */

    /**
     * draft -> contributing + moderator gets explicit rights for edit and contributor does not have anymore. <br/>
     * If there are no defined moderators, this will delegate to submitForValidation.
     * 
     * @param document is the draft document which needs to pass in moderating state
     * @throws XWikiException
     */
    public boolean submitForModeration(DocumentReference document) throws XWikiException;

    /**
     * moderating -> draft + contributor gets edit rights back
     * 
     * @param document
     * @param reason
     * @return
     * @throws XWikiException
     */
    public boolean refuseModeration(DocumentReference document, String reason) throws XWikiException;

    /**
     * moderating -> validating. + moderator looses edit rights
     * 
     * @param document
     * @return
     * @throws XWikiException
     */
    public boolean submitForValidation(DocumentReference document) throws XWikiException;

    /**
     * validating -> draft. + contributor and moderator get back rights
     * 
     * @param documnet
     * @param reason
     * @return
     * @throws XWikiException
     */
    public boolean refuseValidation(DocumentReference documnet, String reason) throws XWikiException;

    /**
     * validating -> published. + document gets copied in its final place where it will be readonly anyway
     * 
     * @param document
     * @return
     * @throws XWikiException
     */
    public DocumentReference publish(DocumentReference document) throws XWikiException;

    /**
     * published -> draft. Published document gets deleted. Content from the published document can be copied to the
     * draft or just ignored, depending on the parameter.
     * 
     * @param document the published document that should be turned to draft.
     * @param forceToDraft
     * @return the draft document
     * @throws XWikiException
     */
    public DocumentReference unpublish(DocumentReference document, boolean forceToDraft) throws XWikiException;

    /**
     * published -> archived. Not yet sure how it would work.
     * 
     * @param document the published document that should be archived.
     * @return
     * @throws XWikiException
     */
    public boolean archive(DocumentReference document) throws XWikiException;

    /**
     * archived -> draft. Archived document gets deleted. Content from the archived document can be copied to the draft
     * or just ignored, depending on the parameter.
     * 
     * @param document the archived document that should get drafted.
     * @return
     * @throws XWikiException
     */
    public DocumentReference unarchive(DocumentReference document, boolean forceToDraft) throws XWikiException;

    /**
     * archived -> published. Not yet sure how it would work.
     * 
     * @param document the archived document that should go back to being published.
     * @return
     * @throws XWikiException
     */
    public boolean publishFromArchive(DocumentReference document) throws XWikiException;
}
