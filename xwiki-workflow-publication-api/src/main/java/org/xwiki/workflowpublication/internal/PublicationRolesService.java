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
import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.script.service.ScriptService;
import org.xwiki.workflowpublication.PublicationRoles;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Roles service to enable roles and access checking, from scripts, and not only.
 * 
 * @version $Id$
 */
@Component
@Named("publicationroles")
@Singleton
public class PublicationRolesService implements ScriptService
{
    @Inject
    private PublicationRoles publicationRoles;

    /**
     * Reference resolver for string representations of references.
     */
    @Inject
    @Named("currentmixed")
    protected DocumentReferenceResolver<String> referenceResolver;

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

    public boolean canValidate(String user, String document)
    {
        try {
            // resolve userReference
            DocumentReference userRef = (DocumentReference) referenceResolver.resolve(user);
            XWikiContext context = getXContext();
            // resolve document reference if any is specified
            XWikiDocument documentObject = null;
            if (!StringUtils.isEmpty(document)) {
                DocumentReference documentRef = (DocumentReference) referenceResolver.resolve(document);
                documentObject = context.getWiki().getDocument(documentRef, context);
            }

            return publicationRoles.canValidate(userRef, documentObject, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting the validation rights for user " + user + " for document "
                + document, e);
            return false;
        }
    }

    public boolean canModerate(String user, String document)
    {
        try {
            // resolve userReference
            DocumentReference userRef = (DocumentReference) referenceResolver.resolve(user);
            XWikiContext context = getXContext();
            // resolve document reference if any is specified
            XWikiDocument documentObject = null;
            if (!StringUtils.isEmpty(document)) {
                DocumentReference documentRef = (DocumentReference) referenceResolver.resolve(document);
                documentObject = context.getWiki().getDocument(documentRef, context);
            }

            return publicationRoles.canModerate(userRef, documentObject, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting the moderation rights for user " + user + " for document "
                + document, e);
            return false;
        }
    }

    public boolean canContribute(String user, String document)
    {
        try {
            // resolve userReference
            DocumentReference userRef = (DocumentReference) referenceResolver.resolve(user);
            XWikiContext context = getXContext();
            // resolve document reference if any is specified
            XWikiDocument documentObject = null;
            if (!StringUtils.isEmpty(document)) {
                DocumentReference documentRef = (DocumentReference) referenceResolver.resolve(document);
                documentObject = context.getWiki().getDocument(documentRef, context);
            }

            return publicationRoles.canContribute(userRef, documentObject, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting the contribution rights for user " + user + " for document "
                + document, e);
            return false;
        }
    }

    public Collection<String> getGroups(DocumentReference userOrGroup, boolean recursive, boolean localGroups,
        boolean userWikiGroups)
    {
        try {
            XWikiContext context = getXContext();

            return publicationRoles.getGroups(userOrGroup, recursive, localGroups, userWikiGroups, context);
        } catch (XWikiException e) {
            logger.error("There was an error getting all the groups for user " + userOrGroup, e);
            return Collections.<String> emptyList();
        }
    }

    public Collection<String> getGroups(String userOrGroup, boolean recursive, boolean localGroups,
        boolean userWikiGroups)
    {
        DocumentReference userRef = (DocumentReference) referenceResolver.resolve(userOrGroup);
        return this.getGroups(userRef, recursive, localGroups, userWikiGroups);
    }

    /**
     * Shortcut method to get the groups regardless of the wiki where they are.
     * 
     * @param userOrGroup
     * @param recursive
     * @return
     */
    public Collection<String> getAllGroups(DocumentReference userOrGroup, boolean recursive)
    {
        return this.getGroups(userOrGroup, recursive, true, true);
    }

    /**
     * Shortcut method to get the groups regardless of the wiki where they are.
     * 
     * @param userOrGroup
     * @param recursive
     * @return
     */
    public Collection<String> getAllGroups(String userOrGroup, boolean recursive)
    {
        return this.getGroups(userOrGroup, recursive, true, true);
    }

    /**
     * @return the xwiki context from the execution context
     */
    private XWikiContext getXContext()
    {
        return (XWikiContext) execution.getContext().getProperty("xwikicontext");
    }
}
