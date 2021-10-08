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

import org.xwiki.bridge.event.AbstractDocumentEvent;
import org.xwiki.model.reference.DocumentReference;
/**
 * This event is sent before a descendant of a workflow document gets published through the publication workflow.
 * It is distinct from {@link DocumentPublishingEvent} in order to distinguish between the publication of the document
 * holding the workflow object and the publication of its descendants: the former is the main event that external
 * applications may listen to, while the latter is used internally by the publication workflow to perform
 * operations on the descendants before publishing, such as updating the references.
 *
 * @version $Id$
 */

public class DocumentChildPublishingEvent extends AbstractDocumentEvent
{
    private static final long serialVersionUID = 1L;

    /**
     * A reference to the page getting published.
     */
    private DocumentReference childReference;

    /**
     * A reference to the workflow document in the context of which the publication occurs.
     */
    private DocumentReference workflowDocumentReference;

    /**
     * Default constructor.
     */
    public DocumentChildPublishingEvent()
    {
        super();
    }

    /**
     * Creates a {@link DocumentChildPublishingEvent}
     * @param childReference a reference to the descendant getting published
     * @param workflowDocumentReference a reference to the workflow in the context of which the event occurs
     */
    public DocumentChildPublishingEvent(DocumentReference childReference, DocumentReference workflowDocumentReference)
    {
        this.childReference = childReference;
        this.workflowDocumentReference = workflowDocumentReference;
    }

    /**
     * @return a reference to the descendant getting published
     */
    public DocumentReference getDocumentChildReference() {
        return childReference;
    }

    /**
     * @return a reference to the workflow in the context of which the event occurs
     */
    public DocumentReference getWorkflowDocumentReference() {
        return workflowDocumentReference;
    }
}
