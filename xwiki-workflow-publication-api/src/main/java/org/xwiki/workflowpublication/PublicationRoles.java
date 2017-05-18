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

import java.util.Collection;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
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

    String getViewers(BaseObject workflowConfig, XWikiContext context);

    String getCommenters(BaseObject workflowConfig, XWikiContext context);

    /**
     * Gets all the groups for the passed user of group, depending on the configuration passed in the parameters. If all
     * the groups are needed, local and global, {@code true} should be passed as the value for the localGroups and
     * userWikiGroups parameters. If the user is a global user, the only groups in subwikis which will be returned are
     * the groups of the current subwiki, and no parameter can influence that, you'll need to change wiki and re-execute
     * this method if you want that to happen.
     * 
     * @param userOrGroup the reference to the user or group for which we want to get the groups
     * @param recursive whether subgroups should be followed or not
     * @param localGroups if this flag is set to true, the groups in the current wiki will be returned, regardless of
     *            where the user is from.
     * @param userWikiGroups if this flag is set to true, the groups in the wiki of the user will be returned. This
     *            makes sense when the current wiki is a subwiki and the user is a global user, when the groups will not
     *            be looked up in the current wiki but only in the main wiki.
     * @param xcontext the xwiki context of this request
     * @return all the groups of the passed user or group
     * @throws XWikiException in case anything goes wrong getting the groups
     */
    Collection<String> getGroups(DocumentReference userOrGroup, boolean recursive, boolean localGroups,
        boolean userWikiGroups, XWikiContext xcontext) throws XWikiException;
}
