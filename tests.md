# Publication Workflow Tests

## Test setup

* Create a workflow configuration with the "hide drafts" option activated

## Terminal page

### Scenario

* Create page in multiple languages with links to other drafts.
* Initialize workflow on that page
* Submit page to moderation, then to validation
* Publish page
* Remove some translations from draft, then republish
* Add rights to the published page, then republish
* Archive page

### Expected results

* The draft page is marked as hidden
* Page rights are updated on each transition along the chosen workflow configuration groups
* The publish action creates a page at the expected location, with translations
* The published page links referring to pages under workflow point at published pages, in the default page as well as
 in the translations, even if the drafts are not published yet
* On second publication, the rights set on the published page are not overriden
* When a translation gets removed from the draft, the corresponding published translation gets removed on next publish
* On page archiving, the page and all its translations become hidden

## Page with children, workflow with children

### Scenario

* Create page in multiple languages containing both non-terminal and terminal children, with at least 3 hierarchy
 levels, with links to other drafts under the same workflow or in other workflows, pointing at either terminal or 
 non-terminal pages, either workflow root pages or child pages
* Initialize workflow on that page and check the "include children" option
* Submit page to moderation, then to validation
* Publish page
* Remove some children and grandchildren from draft, then republish
* Add rights to the published page hierarchy, then republish
* Archive page
* Publish page from archive

### Expected results

* Page draft and its children are marked as hidden
* Page hierarchical rights (global rights in `WebPreferences`) are updated on each transition along the chosen workflow
 configuration groups
* The publish action creates a page at the expected location with its children and grandchildren published as well, 
 and with a history message reflecting the publish action
* Links in the published pages pointing at pages which are under workflow point exclusively at published pages, not
 draft ones, even if the drafts are not published yet
* When draft page children or grandchildren get removed, the corresponding published children or grandchildren get
 removed on next publish
* When rights are updated on the published page hierarchy, they are preserved on next publish
* On page archiving, the main page, the children and grandchildren get hidden
* On page publication from archive, the main page, its children and grandchildren get unhidden 

## Page with children, workflow without children

### Scenario

* Create page with children
* Initialize workflow without checking the "include children" option
* Moderate and publish page

### Expected results

* Local rights are used on the draft page, not hierarchical ones
* On publish, the page under workflow gets published, but not its children