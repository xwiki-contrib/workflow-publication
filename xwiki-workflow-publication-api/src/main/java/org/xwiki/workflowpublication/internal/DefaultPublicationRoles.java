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

import java.util.Collection;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.workflowpublication.PublicationRoles;
import org.xwiki.workflowpublication.WorkflowConfigManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.plugin.rightsmanager.RightsManagerPluginApi;

/**
 * @version $Id$
 */
public class DefaultPublicationRoles implements PublicationRoles
{
    public static final String WF_CONFIG_MODERATOR = "moderator";

    public static final String WF_CONFIG_CONTRIBUTOR = "contributor";

    public static final String WF_CONFIG_VALIDATOR = "validator";

    /**
     * The logger to log.
     */
    @Inject
    private Logger logger;

    /**
     * Local reference string serializer.
     */
    @Inject
    @Named("local")
    protected EntityReferenceSerializer<String> localStringSerializer;

    /**
     * Reference string serializer.
     */
    @Inject
    protected EntityReferenceSerializer<String> stringSerializer;

    @Inject
    protected WorkflowConfigManager configManager;

    @Inject
    @Named("explicit")
    protected DocumentReferenceResolver<String> explicitStringDocRefResolver;

    /**
     * Checks if the passed member is in the passed group.
     * 
     * @param userRef the document reference of the current user
     * @param groupRef the group reference of the group to check
     * @param rightsManager the rights manager plugin used to do the test
     * @return {@code true} if the userRef is in groupRef, {@code false} otherwise
     * @throws XWikiException in case smth wrong happes while accessing groups docs et all
     */
    private boolean isInGroup(DocumentReference userRef, DocumentReference groupRef,
        RightsManagerPluginApi rightsManager) throws XWikiException
    {
        Collection<String> members = rightsManager.getAllMembersNamesForGroup(stringSerializer.serialize(groupRef));
        // if the user is in the same wiki as the group and the local user name is in the group members list or if the
        // members list contains the user full prefixed name
        return (userRef.getWikiReference().getName().equals(groupRef.getWikiReference().getName()) && members
            .contains(localStringSerializer.serialize(userRef)))
            || members.contains(stringSerializer.serialize(userRef));
    }

    /**
     * TODO: return a list, maybe?
     * 
     * @param document
     * @param workflowConfig
     * @param context
     * @return
     * @throws XWikiException
     */
    protected String getRoleGroup(XWikiDocument document, BaseObject workflowConfig, String role, XWikiContext context)
        throws XWikiException
    {
        BaseObject wfConfig = workflowConfig;
        if (wfConfig == null) {
            wfConfig = configManager.getWorkflowConfigForWorkflowDoc(document, context);
        }

        if (wfConfig != null) {
            return wfConfig.getStringValue(role);
        }

        return null;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.workflowpublication.PublicationRoles.CNFPTRoles#canModerate(org.xwiki.model.reference.DocumentReference,
     *      com.xpn.xwiki.doc.XWikiDocument, com.xpn.xwiki.XWikiContext)
     */
    public boolean canModerate(DocumentReference userRef, XWikiDocument document, XWikiContext context)
    {
        try {
            // get the workflow config
            BaseObject workflowConfig = configManager.getWorkflowConfigForWorkflowDoc(document, context);
            // if there is no workflow config, all that can edit can moderate
            if (workflowConfig == null) {
                return context
                    .getWiki()
                    .getRightService()
                    .hasAccessLevel("edit", localStringSerializer.serialize(userRef),
                        localStringSerializer.serialize(document.getDocumentReference()), context);
            }

            // xwiki admins can moderate
            if (hasXWikiAdmin(stringSerializer.serialize(userRef), context)) {
                return true;
            }

            RightsManagerPluginApi rightsManager =
                (RightsManagerPluginApi) context.getWiki().getPluginApi("rightsmanager", context);

            // if is moderator or validator, can moderate
            // check if user is moderator
            boolean isModerator = false;
            String moderatorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_MODERATOR, context);
            if (!StringUtils.isEmpty(moderatorGroup)) {
                isModerator =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(moderatorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            // if we already have it, don't bother to look further
            if (isModerator) {
                return isModerator;
            }
            // check if user is validator
            boolean isValidator = false;
            String validatorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_VALIDATOR, context);
            if (!StringUtils.isEmpty(validatorGroup)) {
                isValidator =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(validatorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            // either moderator or validator can validate
            return isModerator || isValidator;
        } catch (XWikiException e) {
            logger.error(
                "There was an error getting the moderation groups for user " + stringSerializer.serialize(userRef)
                    + " for document " + stringSerializer.serialize(document.getDocumentReference()), e);
        }

        return false;
    }

    @Override
    public boolean canValidate(DocumentReference userRef, XWikiDocument document, XWikiContext context)
    {
        try {
            // get the workflow config
            BaseObject workflowConfig = configManager.getWorkflowConfigForWorkflowDoc(document, context);
            // if there is no workflow config, all that can edit can validate
            if (workflowConfig == null) {
                return context
                    .getWiki()
                    .getRightService()
                    .hasAccessLevel("edit", localStringSerializer.serialize(userRef),
                        localStringSerializer.serialize(document.getDocumentReference()), context);
            }

            // xwiki admins can validate
            if (hasXWikiAdmin(stringSerializer.serialize(userRef), context)) {
                return true;
            }

            RightsManagerPluginApi rightsManager =
                (RightsManagerPluginApi) context.getWiki().getPluginApi("rightsmanager", context);

            // check if user is validator
            boolean isValidator = false;
            String validatorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_VALIDATOR, context);
            if (!StringUtils.isEmpty(validatorGroup)) {
                isValidator =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(validatorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            return isValidator;
        } catch (XWikiException e) {
            logger.error(
                "There was an error getting the validation group for user " + stringSerializer.serialize(userRef)
                    + " for document " + stringSerializer.serialize(document.getDocumentReference()), e);
        }

        return false;
    }

    @Override
    public boolean canContribute(DocumentReference userRef, XWikiDocument document, XWikiContext context)
    {
        try {
            // get the workflow config
            BaseObject workflowConfig = configManager.getWorkflowConfigForWorkflowDoc(document, context);
            // if there is no workflow config, all that can edit can contribute
            if (workflowConfig == null) {
                return context
                    .getWiki()
                    .getRightService()
                    .hasAccessLevel("edit", localStringSerializer.serialize(userRef),
                        localStringSerializer.serialize(document.getDocumentReference()), context);
            }

            // xwiki admins can contribute
            if (hasXWikiAdmin(stringSerializer.serialize(userRef), context)) {
                return true;
            }

            RightsManagerPluginApi rightsManager =
                (RightsManagerPluginApi) context.getWiki().getPluginApi("rightsmanager", context);

            // check if user is contributor or moderator or validator
            boolean isContributor = false;
            String contributorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_CONTRIBUTOR, context);
            if (!StringUtils.isEmpty(contributorGroup)) {
                isContributor =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(contributorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            if (isContributor) {
                return true;
            }
            boolean isModerator = false;
            String moderatorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_MODERATOR, context);
            if (!StringUtils.isEmpty(moderatorGroup)) {
                isModerator =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(moderatorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            if (isModerator) {
                return true;
            }
            boolean isValidator = false;
            String validatorGroup = getRoleGroup(document, workflowConfig, WF_CONFIG_VALIDATOR, context);
            if (!StringUtils.isEmpty(validatorGroup)) {
                isValidator =
                    isInGroup(userRef,
                        explicitStringDocRefResolver.resolve(validatorGroup, document.getDocumentReference()),
                        rightsManager);
            }
            return isValidator;
        } catch (XWikiException e) {
            logger.error(
                "There was an error getting the contribution group for user " + stringSerializer.serialize(userRef)
                    + " for document " + stringSerializer.serialize(document.getDocumentReference()), e);
        }

        return false;
    }

    /**
     * Checks if the current user has XWiki admin rights on the wiki. Since the hasAdminRights returns space admin as
     * well, we need to have this here.
     * 
     * @param user the serialized user name to check admin rights for
     * @param context the xwiki context of the current request
     * @return true if the user has admin rights on the wiki, false otherwise
     */
    private boolean hasXWikiAdmin(String user, XWikiContext context)
    {
        try {
            return context.getWiki().getRightService().hasAccessLevel("admin", user, "XWiki.XWikiPreferences", context);
        } catch (Exception e) {
            logger.error("Failed to check wiki admin right for user [" + user + "]", e);
            return false;
        }
    }

    @Override
    public String getContributors(BaseObject workflowConfig, XWikiContext context)
    {
        if (workflowConfig == null) {
            return null;
        }

        return workflowConfig.getStringValue(WF_CONFIG_CONTRIBUTOR);
    }

    @Override
    public String getModerators(BaseObject workflowConfig, XWikiContext context)
    {
        if (workflowConfig == null) {
            return null;
        }

        return workflowConfig.getStringValue(WF_CONFIG_MODERATOR);
    }

    @Override
    public String getValidators(BaseObject workflowConfig, XWikiContext context)
    {
        if (workflowConfig == null) {
            return null;
        }

        return workflowConfig.getStringValue(WF_CONFIG_VALIDATOR);
    }
}
