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
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Exposes functions that help manipulate the roles of users in the publication workflow.
 * 
 * @version $Id $
 */
@Role
public interface PublicationRoles
{
    /**
     * Java API to find out if the user designated by userRef can moderate the document designated by document.
     * 
     * @param userRef the reference to the user to check moderation access for
     * @param document the document to moderate. Pass null if you need to check whether the user can moderate any
     *            document (i.e. is in moderators group)
     * @param context the context of the request
     * @return true if the user can moderate, false otherwise
     */
    boolean canModerate(DocumentReference userRef, XWikiDocument document, XWikiContext context);

    boolean canValidate(DocumentReference userRef, XWikiDocument document, XWikiContext context);
    
    boolean canContribute(DocumentReference userRef, XWikiDocument document, XWikiContext context);
    
    String getContributors(BaseObject workflowConfig, XWikiContext context);
    
    String getModerators(BaseObject workflowConfig, XWikiContext context);
    
    String getValidators(BaseObject workflowConfig, XWikiContext context);
}
