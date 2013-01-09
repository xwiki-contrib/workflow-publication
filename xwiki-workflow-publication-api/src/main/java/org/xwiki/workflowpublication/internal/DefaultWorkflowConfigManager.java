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

import org.apache.commons.lang.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.workflowpublication.PublicationWorkflow;
import org.xwiki.workflowpublication.WorkflowConfigManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * @version $Id$
 */
@Component
public class DefaultWorkflowConfigManager implements WorkflowConfigManager
{
    public static final String WF_CONFIG_REF_FIELDNAME = "workflow";

    @Inject
    @Named("currentmixed")
    protected DocumentReferenceResolver<String> currentMixedStringDocRefResolver;

    /**
     * The current entity reference resolver, to resolve the notions class reference.
     */
    @Inject
    @Named("current/reference")
    protected DocumentReferenceResolver<EntityReference> currentReferenceEntityResolver;

    @Override
    public BaseObject getWorkflowConfig(String workflowConfigName, XWikiContext context) throws XWikiException
    {
        if (!StringUtils.isEmpty(workflowConfigName)) {
            XWikiDocument wfConfigDoc =
                context.getWiki().getDocument(currentMixedStringDocRefResolver.resolve(workflowConfigName), context);
            if (wfConfigDoc != null) {
                BaseObject wfConfigRef =
                    wfConfigDoc.getXObject(currentReferenceEntityResolver.resolve(PUBLICATION_WORKFLOW_CONFIG_CLASS));
                return wfConfigRef;
            }
        }

        return null;
    }

    @Override
    public BaseObject getWorkflowConfigForWorkflowDoc(XWikiDocument document, XWikiContext context)
        throws XWikiException
    {
        BaseObject workflowInstance =
            document.getXObject(currentReferenceEntityResolver.resolve(PublicationWorkflow.PUBLICATION_WORKFLOW_CLASS));
        if (workflowInstance != null) {
            String workflowConfigReference = workflowInstance.getStringValue(WF_CONFIG_REF_FIELDNAME);
            return getWorkflowConfig(workflowConfigReference, context);
        }

        return null;
    }
}
