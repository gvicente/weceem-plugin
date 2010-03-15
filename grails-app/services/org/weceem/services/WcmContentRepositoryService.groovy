package org.weceem.services

import org.codehaus.groovy.grails.commons.ApplicationHolder

import org.springframework.beans.factory.InitializingBean
import grails.util.Environment
// This is for a hack, remove later
import org.codehaus.groovy.grails.web.metaclass.BindDynamicMethod
import org.hibernate.exception.ConstraintViolationException

import org.weceem.content.*

//@todo design smell!


import org.weceem.files.*
import org.weceem.script.WcmScript

import org.weceem.security.*
import org.codehaus.groovy.grails.web.context.ServletContextHolder

/**
 * WcmContentRepositoryService class provides methods for WcmContent Repository tree
 * manipulations.
 * The service deals with WcmContent and subclasses of WcmContent classes.
 *
 * @author Sergei Shushkevich
 */
class WcmContentRepositoryService implements InitializingBean {

    static final CONTENT_CLASS = WcmContent.class.name
    static final STATUS_ANY_PUBLISHED = 'published'
    
    static CACHE_NAME_GSP_CACHE = "gspCache"
    static CACHE_NAME_URI_TO_CONTENT_ID = "uriToContentCache"
    
    static transactional = true

    def uriToIdCache
    def gspClassCache
    
    def grailsApplication
    def wcmImportExportService
    def wcmCacheService
    def groovyPagesTemplateEngine
    def wcmSecurityService
    def wcmEventService
    
    static DEFAULT_STATUSES = [
        [code:100, description:'draft', publicContent:false],
        [code:200, description:'reviewed', publicContent:false],
        [code:300, description:'approved', publicContent:false],
        [code:400, description:'published', publicContent:true]
    ]
    
    void afterPropertiesSet() {
        uriToIdCache = wcmCacheService.getCache(CACHE_NAME_URI_TO_CONTENT_ID)
        assert uriToIdCache
        gspClassCache = wcmCacheService.getCache(CACHE_NAME_GSP_CACHE)
        assert gspClassCache
    }
    
    void createDefaultSpace() {
        if (Environment.current != Environment.TEST) {
            if (WcmSpace.count() == 0) {
                createSpace([name:'Default'])
            }
        }
    }
    
    void createDefaultStatuses() {
        if (WcmStatus.count() == 0) {
            DEFAULT_STATUSES.each {
                assert new WcmStatus(it).save()
            }
        }
    }
    
    List getAllPublicStatuses() {
        WcmStatus.findAllByPublicContent(true, [cache:true])
    }
    
    WcmSpace findDefaultSpace() {
        def space
        def spaces = WcmSpace.list([cache:true])
        if (spaces) {
            space = spaces[0]
        }        
        return space
    }
    
    WcmSpace findSpaceByURI(String uri) {
        WcmSpace.findByAliasURI(uri, [cache:true])
    }
    
    Map resolveSpaceAndURI(String uri) {
        def spaceName
        def space
        
        if (uri?.startsWith('/')) {
            if (uri.length() > 1) {
                uri = uri[1..uri.length()-1]
            } else {
                uri = ''
            }
        }
        def n = uri?.indexOf('/')
        if (n >= 0) {
            spaceName = uri[0..n-1]
            if (n < uri.size()-1) {
                uri = uri[n+1..-1]
            }
        }
        
        // Let's try to find the space, or page in the root space
        if (!spaceName) {
            if (log.debugEnabled) {
                log.debug "WcmContent request for no space, looking for space with blank aliasURI"
            }
            space = findSpaceByURI('')
            if (!space) {
                if (log.debugEnabled) {
                    log.debug "WcmContent request for no space, looking for any space, none with blank aliasURI"
                }
                space = findDefaultSpace()
            }
        } else {
            if (log.debugEnabled) {
                log.debug "Content request for space with alias: ${spaceName}"
            }
            space = findSpaceByURI(spaceName)

            // Check for case where requesting a doc that is in a space mapped to uri ""
            if (space == null) {
                if (log.debugEnabled) {
                    log.debug "WcmContent request has no space found in database, looking for space with blank aliasURI to see if doc is there"
                }
                space = findSpaceByURI('')
                if (space) {
                    uri = uri ? spaceName + '/' + uri : spaceName
                    if (log.debugEnabled) {
                        log.debug "Content request has found space with blank aliasURI, amending uri to include the space name: ${uri}"
                    }
                }
            }
        }        

        // If the URI is just for the space uri with no doc, default to "index" node in root of spacer
        if ((uri == null) || (uri == space?.aliasURI) || (uri == space?.aliasURI+'/')) { 
            uri = 'index'
        }

        [space:space, uri:uri]
    }
    
    WcmSpace createSpace(params, templateName = 'default') {
        def s
        WcmContent.withTransaction { txn ->
            s = new WcmSpace(params)
            if (s.save()) {
                // Create the filesystem folder for the space
                def spaceDir = grailsApplication.parentContext.getResource(
                    "${WcmContentFile.DEFAULT_UPLOAD_DIR}/${s.makeUploadName()}").file
                if (!spaceDir.exists()) {
                    spaceDir.mkdirs()
                }

                if (templateName) {
                    importSpaceTemplate('default', s)
                }
            } else {
                log.error "Unable to create space with properties: ${params} - errors occurred: ${s.errors}"
            }
        }
        return s // If this fails we still return the original space so we can see errors
    }
    
    /**
     * Import a named space template (import zip) into the specified space
     */
    void importSpaceTemplate(String templateName, WcmSpace space) {
        log.info "Importing space template [${templateName}] into space [${space.name}]"
        // For now we only load files, in future we may get them as blobs from DB
        def f = File.createTempFile("default-space-import", null)
        def resourceName = "classpath:/org/weceem/resources/${templateName}-space-template.zip"
        def res = ApplicationHolder.application.parentContext.getResource(resourceName).inputStream
        if (!res) {
            log.error "Unable to import space template [${templateName}] into space [${space.name}], space template not found at resource ${resourceName}"
            return
        }
        f.withOutputStream { os ->
            os << res
        }
        try {
            wcmImportExportService.importSpace(space, 'simpleSpaceImporter', f)
        } catch (Throwable t) {
            log.error "Unable to import space template [${templateName}] into space [${space.name}]", t
            throw t // rethrow, this is sort of fatal
        }
        log.info "Successfully imported space template [${templateName}] into space [${space.name}]"
    }
    
    void requirePermissions(WcmSpace space, permissionList, Class<WcmContent> type = null) throws AccessDeniedException {
        if (!wcmSecurityService.hasPermissions(space, permissionList, type)) {
            throw new AccessDeniedException("User [${wcmSecurityService.userName}] with roles [${wcmSecurityService.userRoles}] does not have the permissions [$permissionList] to access space [${space.name}]")
        }
    }       
    
    void requirePermissions(WcmContent content, permissionList, Class<WcmContent> type = null) throws AccessDeniedException {
        if (!wcmSecurityService.hasPermissions(content, permissionList, type)) {
            throw new AccessDeniedException("User [${wcmSecurityService.userName}] with roles [${wcmSecurityService.userRoles}] does not have the permissions [$permissionList] to access content at [${content.absoluteURI}] in space [${content.space.name}]")
        }
    }       

    void deleteSpaceContent(WcmSpace space) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_ADMIN])        

        log.info "Deleting content from space [$space]"
        // Let's brute-force this
        // @todo remove/rework this for 0.2
        def contentList = WcmContent.findAllBySpace(space)
        for (content in contentList){
            // Invalidate the caches before parent is changedBy
            invalidateCachingForURI(content.space, content.absoluteURI)

            content.parent = null
            content.save()
        }
        def wasDelete = true
        while (wasDelete){
            contentList = WcmContent.findAllBySpace(space)
            wasDelete = false
            for (content in contentList){
                def refs = findReferencesTo(content)
                if (refs.size() == 0){
                    deleteNode(content)
                    wasDelete = true
                }
            }
        }
        log.info "Finished Deleting content from space [$space]"
    }
    
    void deleteSpace(WcmSpace space) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_ADMIN])        

        // Delete space content
        deleteSpaceContent(space)
        // Delete space
        space.delete(flush: true)
    }

    def getGSPTemplate(content) {
        def absURI = content.absoluteURI
        wcmCacheService.getOrPutObject(CACHE_NAME_GSP_CACHE, makeURICacheKey(content.space, absURI)) {
            if (log.debugEnabled) {
                log.debug "Creating GSP template class for $absURI"
            }
            // Workaround for Grails 1.2.0 bug wher page name must be a valid local system file path!
            // Was dying on Windows with / in uris. http://jira.codehaus.org/browse/GRAILS-5772
            groovyPagesTemplateEngine.createTemplate(content.content, ('WcmContent:'+absURI).replaceAll(/[^a-zA-Z0-9\-]/, '_') )
        }
    }

    /**
     * Take a string or Class or null and turn it into a content Class
     */
    // @todo cache the list of known type ans mappings that are assignable to a WcmContent variable
    // so that we can skip the isAssignableFrom which will affect performance a lot, as this function may be
    // called a lot
    Class getContentClassForType(def type) {
        if (type == null) {
            return WcmContent.class
        }        
        
        def cls = (type instanceof Class) ? type : grailsApplication.getClassForName(type)
        if (cls) {
            if (!WcmContent.isAssignableFrom(cls)) {
                throw new IllegalArgumentException("The class $clazz does not extend Content")
            } else {
                return cls
            }
        } else {
            throw new IllegalArgumentException("There is no content class with name $type")
        }
    }

    List listContentClassNames(Closure precondition = null) {
        return listContentClasses(precondition).collect { it.name }
    }
    
    List listContentClasses(Closure precondition = null) {
        def results = []
        grailsApplication.domainClasses.each { dc ->
            def cls = dc.clazz
            if (WcmContent.isAssignableFrom(cls) && (cls != WcmContent)) {
                if ((precondition == null) || precondition(cls)) {
                    results << cls
                }
            }
        }
        return results
    }
    
    WcmContent newContentInstance(String typename, WcmSpace space = null) {
        def cls = getContentClassForType(typename)
        def c = cls.newInstance()
        if (space) {
            c.space = space
        }
        return c
    }
    
    /**
     * Prepares map of content properties.
     * In the future, we may need more information for the nodes,
     * eg. incoming links
     */
    Map getContentDetails(WcmContent content) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        return [id: content.id, className: content.class.name,
                title: content.title, createdBy: content.createdBy,
                createdOn: content.createdOn, changedBy: content.changedBy,
                changedOn: content.changedOn,
                // @todo replace this ugliness with polymorphic calls
                summary: content.metaClass.hasProperty(content, 'summary') ? content.summary : null,
                contentType: content.class.name]
    }

    /**
     * Returns map of related content properties.
     *
     * @param content
     */
    Map getRelatedContent(WcmContent content) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        def result = [:]
        // @todo change to criteria/select
        result.parents = WcmVirtualContent.findAllByTarget(content)*.parent
        if (content.parent) result.parents << content.parent
        result.children = content.children
        
        def relatedContents = []
        // @todo replace with more efficient select/criteria
        relatedContents.addAll(WcmRelatedContent.findAllWhere(targetContent: content).collect {
            it.sourceContent
        })
        // @todo replace with more efficient select/criteria
        relatedContents.addAll(WcmRelatedContent.findAllWhere(sourceContent: content).collect {
            it.targetContent
        })
        result.related = relatedContents.unique()

        return result
    }

    /**
     * Returns map of recent changes for specified WcmContent.
     *
     * @param content
     */
    Map getRecentChanges(WcmContent content) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        def changes = WcmContentVersion.findAllByObjectKey(content.ident(),
                [sort: 'revision', order: 'desc'])
        return [changes: changes]
    }

    /**
     * Creates new WcmContent node and it's relation from request parameters
     *
     * @param content
     */
    def createNode(String type, def params, Closure postInit = null) {
        def content = newContentInstance(type)
        hackedBindData(content, params)
        if (params.tags != null) {
            content.setTags(params.tags.tokenize(',').collect { it.trim().toLowerCase()} )
        }
        if (postInit) {
            postInit(content)
        }
        createNode(content, content.parent)
        return content
    }

    /**
     * Creates new WcmContent node and it's relation
     *
     * @param content
     * @param parentContent
     */
    Boolean createNode(WcmContent content, WcmContent parentContent = null) {
        requirePermissions(parentContent ?: content.space, [WeceemSecurityPolicy.PERMISSION_CREATE])        

        if (parentContent == null) parentContent = content.parent

        if (log.debugEnabled) {
            log.debug "Creating node: ${content.dump()} with parent [$parentContent]"
        }
        
        def result 
        if (content.metaClass.respondsTo(content, 'create', WcmContent)) {
            if (log.debugEnabled) {
                log.debug "Creating node, type ${content.class} support 'create' event, calling"
            }
            // Call the event so that nodes can perform post-creation tasks
            result = content.create(parentContent)
        } else {
            if (log.debugEnabled) {
                log.debug "Creating node, type ${content.class} does not support 'create' event, skipping"
            }
            result = true
        }

        if (result) {
            // @todo This is not safe in concurrent environments, you can end up with 2 
            // nodes with same orderIndex - which is not prevented by constraints but may be annoying for users
            // Try to update this to use executeUpdate to set the index, at some point
            def orderIndex = -1
            WcmContent.withNewSession {
                def criteria = WcmContent.createCriteria()
                def nodes = criteria {
                    if (parentContent) {
                        eq("parent", parentContent)
                    } else {
                        isNull("parent")
                    }
                    maxResults(1)
                    order("orderIndex", "desc")
                }
                orderIndex = nodes ? nodes[0].orderIndex + 1 : 0
            }
            content.orderIndex = orderIndex

            if (parentContent) {
                parentContent.addToChildren(content)
            }

            // We complete the AliasURI, AFTER handling the create() event which may need to affect title/aliasURI
            if (!content.aliasURI) {
                content.createAliasURI(parentContent)
            }
            
            // We must have generated aliasURI and set parent here to be sure that the uri is unique
            boolean saved = false
            int attempts = 0
            while (!saved && (attempts++ < 100)) {
                try {
                    if (content.save(flush:true)) {
                        saved = true
                    }
                } catch (ConstraintViolationException cve) {
                    // See if we get a new aliasURI from the content, and if so try again
                    def oldAliasURI = content.aliasURI
                    content.createAliasURI(parentContent)
                    if (oldAliasURI != content.aliasURI) {
                        if (log.warnEnabled) {
                            log.warn "Failed to create new content ${content.dump()} due to constraint violation, trying again with new aliasURI"
                        }
                    } else {
                        log.error "Failed to create new content ${content.dump()} due to constraint violation, giving up as aliasURI is invariant"
                        result = false
                        break;
                    }
                }
            }

            if (!result) {
                parent.discard() // revert the changes we made to parent
            }
        }
        
        if (result) {
            wcmEventService.afterContentAdded(content)
        }
        return result
    }

    /**
     * Changes node's title.
     *
     * @param content
     * @param oldTitle
     */
    Boolean renameNode(WcmContent content, oldTitle) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_EDIT])        

        if (content.metaClass.respondsTo(content, 'rename', String)) {
            return content.rename(oldTitle)
        } else {
            return true
        }
    }

    /**
     * Creates new virtual copy of a content node.
     *
     * @todo rename to createNodeReference? virtualCopyNode?
     *
     * @param sourceContent
     * @param targetContent
     * @return new instance of VirtualContentNode or null if there were errors
     */
    WcmVirtualContent linkNode(WcmContent sourceContent, WcmContent targetContent, orderIndex) {
        // Check they can create under the target
        requirePermissions(targetContent, [WeceemSecurityPolicy.PERMISSION_CREATE])        
        requirePermissions(sourceContent, [WeceemSecurityPolicy.PERMISSION_VIEW])        

        if (sourceContent == null){
            return null
        }
        if (sourceContent && (sourceContent instanceof WcmVirtualContent)) {
            sourceContent = sourceContent.target
        }
        if (WcmContent.findWhere(parent: targetContent, aliasURI: sourceContent.aliasURI + "-copy") != null){
            return null
        }
        WcmVirtualContent vcont = new WcmVirtualContent(title: sourceContent.title,
                                          aliasURI: sourceContent.aliasURI + "-copy",
                                          target: sourceContent, status: sourceContent.status, 
                                          space: sourceContent.space)
        WcmContent inPoint = WcmContent.findByOrderIndexAndParent(orderIndex, targetContent)
        if (inPoint != null){
            shiftNodeChildrenOrderIndex(targetContent, orderIndex)
        }
        vcont.orderIndex = orderIndex
        if (targetContent) {
            if (WcmVirtualContent.findWhere(parent: targetContent, target: sourceContent)){
                return null
            }
            targetContent.addToChildren(vcont)
            if (!vcont.save()){
                return null
            }
            if (!targetContent.save(flush:true)){
                return null
            }
        }else{
            if (!vcont.save(flush: true)){
                return null
            }
        }
        return vcont
    }

    /**
     * Changes content node reference.
     *
     * @param sourceContent
     * @param targetContent
     */
    Boolean moveNode(WcmContent sourceContent, WcmContent targetContent, orderIndex) {
        if (targetContent) {
            requirePermissions(targetContent, [WeceemSecurityPolicy.PERMISSION_CREATE])        
        }
        requirePermissions(sourceContent, [WeceemSecurityPolicy.PERMISSION_EDIT,WeceemSecurityPolicy.PERMISSION_VIEW])        

        if (!sourceContent) return false
        if (!targetContent){
            def criteria = WcmContent.createCriteria()
            def nodes = criteria {
                if (targetContent){
                    eq("parent.id", targetContent.id)
                }else{
                    isNull("parent")
                }
                eq("aliasURI", sourceContent.aliasURI)
                not{
                    eq("id", sourceContent.id)
                }
            }
            
            if (nodes.size() > 0){
                return false
            } 
        }
        if (sourceContent.metaClass.respondsTo(sourceContent, "move", WcmContent)){
            sourceContent.move(targetContent)
        }
        def parent = sourceContent.parent
        if (parent) {
            parent.children.remove(sourceContent)
            sourceContent.parent = null
            assert parent.save()
        }
        WcmContent inPoint = WcmContent.findByOrderIndexAndParent(orderIndex, targetContent)
        if (inPoint != null){
            shiftNodeChildrenOrderIndex(targetContent, orderIndex)
        }
        sourceContent.orderIndex = orderIndex
        if (targetContent) {
            if (!targetContent.children) targetContent.children = new TreeSet()
            targetContent.addToChildren(sourceContent)
            assert targetContent.save()
        }
        return sourceContent.save(flush: true)
     }
     
    def shiftNodeChildrenOrderIndex(parent = null, shiftedOrderIndex){
        // Can't do this until space is supplied
        //requirePermissions(parent, [WeceemSecurityPolicy.PERMISSION_EDIT])        
        // @todo this is probably flushing the session with incomplete changes - use withNewSession?
        def criteria = WcmContent.createCriteria()
        def nodes = criteria {
            if (parent){
                eq("parent.id", parent.id)
            }else{
                isNull("parent")
            }
            ge("orderIndex", shiftedOrderIndex)
            order("orderIndex", "asc")
        }
        def orderIndex = shiftedOrderIndex
        nodes.each{it->
            it.orderIndex = ++orderIndex
            it.save()
        }
    }
    
    
    /**
     * Use introspection to find all references to the specified content. Requires finding all
     * associations/relationships to other WcmContent and querying them all individually. Hideous but
     * less ugly than forcing all references to be ContentRef(s) we decided.
     */
    ContentReference[] findReferencesTo(WcmContent content) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        
        def results = [] 
        // @todo this will perform rather poorly. We should find all assocation properties FIRST
        // and then run a query for each association, which - with caching - should run a lot faster than
        // checking every property on every node
        for (cont in WcmContent.list()){
            def perProps = grailsApplication.getDomainClass(cont.class.name).persistentProperties.findAll { p -> 
                p.isAssociation() && WcmContent.isAssignableFrom(p.referencedPropertyType)
            }
            for (p in perProps){
                if (cont."${p.name}" instanceof Collection){
                    for (inst in cont."${p.name}"){
                        if (inst.equals(content)){
                            results << new ContentReference(referringProperty: p.name, referencingContent: cont, targetContent: content)
                        }
                    }
                }else{
                    if (content.equals(cont."${p.name}")){
                        results << new ContentReference(referringProperty: p.name, referencingContent: cont, targetContent: content)
                    }
                }
            }
        }
        return results as ContentReference[]
    }
    
    /**
     * Deletes content node and all it's references.
     * All children of sourceContent will be assigned to all its parents.
     *
     * @param sourceContent
     */
    Boolean deleteNode(WcmContent sourceContent) {
        if (!sourceContent) return Boolean.FALSE
        
        requirePermissions(sourceContent, [WeceemSecurityPolicy.PERMISSION_DELETE])        
        
        // Create a versioning entry
        sourceContent.saveRevision(sourceContent.title, sourceContent.space.name)
        
        if (sourceContent.metaClass.respondsTo(sourceContent, 'deleteContent')) {
            if (!sourceContent.deleteContent()) return false
        }

        // Do this now before absoluteURI gets trashed by changing the parent
        invalidateCachingForURI(sourceContent.space, sourceContent.absoluteURI)

        def parent = sourceContent.parent

        // if there is a parent  - we delete node from its association
        if (parent) {
            parent.children = parent.children.findAll{it-> it.id != sourceContent.id}
            assert parent.save()
        }

        // we need to delete all virtual contents that reference sourceContent
        def copies = WcmVirtualContent.findAllWhere(target: sourceContent)
        copies?.each() {
           if (it.parent) {
               parent = WcmContent.get(it.parent.id)
               parent.children.remove(it)
           }
           it.delete()
        }

        // delete node
        
        // @todo replace this with code that looks at all the properties for relationships
        if (sourceContent.metaClass.hasProperty(sourceContent, 'template')?.type == WcmTemplate) {
            sourceContent.template = null
        }
        if (sourceContent.metaClass.hasProperty(sourceContent, 'target')?.type == WcmContent) {
            sourceContent.target = null
        }

        sourceContent.delete(flush: true)

        wcmEventService.afterContentRemoved(sourceContent)

        return true
    }

    /**
     * Deletes content reference 
     *
     * @todo Update the naming of this, "link" is not correct terminology. 
     *
     * @param child
     * @param parent
     */
    void deleteLink(WcmContent child, WcmContent parent) {
        requirePermissions(parent, [WeceemSecurityPolicy.PERMISSION_EDIT])        
        requirePermissions(child, [WeceemSecurityPolicy.PERMISSION_EDIT])        

        // remove child from association
        parent.children?.remove(child)
        parent.save()
        
        // change reference to parent
        def parentRef = parent.parent
        child.parent = parentRef

        // update orderIndex for new associatio
        def newIndex = parentRef?.children?.last()?.orderIndex ?
                           parentRef?.children?.last()?.orderIndex + 1 : 0
        child.orderIndex = newIndex        

        if (parentRef) {
            parentRef.children << child
            parentRef.save()
        }

    }
    
    def updateSpace(def id, def params){
        def space = WcmSpace.get(id)
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_ADMIN])        

        if (space){
            def oldAliasURI = space.makeUploadName()
            hackedBindData(space, params)
            if (!space.hasErrors() && space.save()) {
                def oldFile = new File(ServletContextHolder.servletContext.getRealPath(
                        "/${WcmContentFile.DEFAULT_UPLOAD_DIR}/${oldAliasURI}"))
                if (oldFile.exists()) {
                    def newFile = new File(ServletContextHolder.servletContext.getRealPath(
                        "/${WcmContentFile.DEFAULT_UPLOAD_DIR}/${space.makeUploadName()}"))
                    oldFile.renameTo(newFile)
                }
                return [space: space]
            } else {
                return [errors:space.errors, space:space]
            }
        }else{
            return [notFound: true]
        }
    }
    
    /**
     * Update a node with the new properties supplied, binding them in using Grails binding
     * @return a map containing an optional "errors" list property and optional notFound boolean property
     */
    def updateNode(String id, def params) {
        WcmContent content = WcmContent.get(id)
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_EDIT])        

        if (content) {
            updateNode(content, params)
        } else {
            return [notFound:true]
        }        
    }
    
    // @todo This is a hack so we can bind without x.properties = y which is broken in production on Grails 1.2-M2
    public hackedBindData(obj, params) {
        new BindDynamicMethod().invoke(this, 'bindData', obj, params)
    }

    String makeURICacheKey(WcmSpace space, uri) {
        space.aliasURI+':'+uri
    }

    void invalidateCachingForURI( WcmSpace space, uri) {
        // If this was content that created a cached GSP class, clear it now
        def key = makeURICacheKey(space,uri)
        log.debug "Removing cached info for cache key [$key]"
        gspClassCache.remove(key) // even if its not a GSP/script lets just assume so, quicker than checking & remove
        uriToIdCache.remove(key)
    }
    
    def updateNode(WcmContent content, def params) {
        requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_EDIT])        

        // firstly we save revision: to prevent errors that we have 2 objects
        // in session with the same identifiers
        if (log.debugEnabled) {
            log.debug("Updating node with id ${content.id}, with parameters: $params")
        }
        def oldAbsURI = content.absoluteURI
        content.saveRevision(params.title ?: content.title, params.space ? WcmSpace.get(params.'space.id')?.name : content.space.name)
        def oldTitle = content.title
        // map in new values
        hackedBindData(content, params)

        if (params.tags != null) {
            content.setTags(params.tags.tokenize(',').collect { it.trim().toLowerCase()} )
        }

        if (content instanceof WcmContentFile){
            content.rename(oldTitle)
        }
        if (log.debugEnabled) {
            log.debug("Updated node with id ${content.id}, properties are now: ${content.dump()}")
        }
        if (content instanceof WcmContentFile){
            content.createAliasURI(content.parent)
        }else
        if (!content.aliasURI && content.title) {
            content.createAliasURI(content.parent)
        }
        def ok = content.validate()
        if (content.save()) {
            if (log.debugEnabled) {
                log.debug("Update node with id ${content.id} saved OK")
            }
            
            invalidateCachingForURI(content.space, oldAbsURI)

            wcmEventService.afterContentUpdated(content)

            return [content:content]
        } else {
            if (log.debugEnabled) {
                log.debug("Update node with id ${content.id} failed with errors: ${content.errors}")
            }
            return [errors:content.errors, content:content]
        }
    }
    
    /**
     * Count child nodes of a given node, where nodes match the type and status (if any) supplied in args
     * Very useful for rendering the number of published comments on an item, for example in blogs.
     */
    def countChildren(WcmContent sourceNode, Map args = null) {
        requirePermissions(sourceNode, [WeceemSecurityPolicy.PERMISSION_VIEW])        

        // for WcmVirtualContent - the children list is a list of target children
        if (sourceNode instanceof WcmVirtualContent) {
            sourceNode = sourceNode.target
        }
        
        def clz = args?.type ? getContentClassForType(args.type) : WcmContent
        return (doCriteria(clz, args?.status, args?.params) {
            projections {
                count('id')
            }
            if (sourceNode) {
                eq('parent', sourceNode)
            } else {
                isNull('parent')
            }
        })[0]
    }
    
    /**
     * Returns the number of content nodes in the given space and matching the indicated parameters.
     * @param space The space to search for content in
     * @param args A map of query parameters (type, status)
     */
    def countContent(WcmSpace space, Map args = null) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        
        def clz = args?.type ? getContentClassForType(args.type) : WcmContent
        return (doCriteria(clz, args?.status, args?.params) {
            projections {
                count('id')
            }
            eq('space', space)
        })[0]
    }
    
    /**
     * Change a criteria closure so that it includes the restrictions specified in the params as per
     * normal grails controller mechanisms - max, offset, sort and order
     */
    private Closure criteriaWithParams(Map params, Closure originalCriteria) {
        return { ->
            originalCriteria.delegate = delegate
            originalCriteria()
            if (params?.max != null) {
                maxResults(params.max)
            }
            if (params?.offset != null) {
                firstResult(params.offset)
            }
            if (params?.sort != null) {
                order(params.sort, params.order ?: 'asc')
            }
        }
    }
    
    /**
     * Wrap a criteria query, adding filtering by status
     * where status can be:
     * - null for 'any' 
     * - a WcmStatus instance eg WcmStatus.get(1)
     * - an integer for a status code eg 500
     * - a list of status codes eg [100, 200, 500]
     * - a range of integer status codes eg (1..500)
     * - a string integer status code eg "500"
     * 
     */
    private def criteriaWithStatus(status, Closure originalCriteria) {
        return { ->
            originalCriteria.delegate = delegate
            originalCriteria()

            if (status != null) {
                if (status == WcmContentRepositoryService.STATUS_ANY_PUBLISHED) {
                    inList('status', allPublicStatuses)
                } else if (status instanceof Collection) {
                    // NOTE: This assumes collection is a collection of codes, not WcmStatus objects
                    inList('status', WcmStatus.findAllByCodeInList(status))
                } else if (status instanceof WcmStatus) {
                    eq('status', status)
                } else if (status instanceof Integer) {
                    eq('status', WcmStatus.findByCode(status) )
                } else if (status instanceof IntRange) {
                    between('status', status.fromInt, status.toInt)
                } else {
                    def s = status.toString()
                    if (s.isInteger()) {
                        eq('status', WcmStatus.findByCode(s.toInteger()) )
                    } else throw new IllegalArgumentException(
                        "The [status] argument must be null (for 'any'), or '${WcmContentRepositoryService.STATUS_ANY_PUBLISHED}',  an integer (or integer string), a collection of codes (numbers), a Status instance or an IntRange. You supplied [$status]")
                }
            }
        }
    }
    
    protected def doCriteria(clz, status, params, Closure c) {
        clz.withCriteria( criteriaWithParams( params, criteriaWithStatus(status, c) ) )
    }
    
    /**
     * Find all the children of the specified node, within the content hierarchy, optionally filtering by a content type class
     * @todo we can probably improve performance by applying the typeRestriction using some HQL
     */ 
    def findChildren(WcmContent sourceNode, Map args = Collections.EMPTY_MAP) {
        if (log.debugEnabled) {
            log.debug "Finding children of ${sourceNode.absoluteURI} with args $args"
        }
        // @todo we also need to filter the result list by VIEW permission too!
        assert sourceNode != null

        // for WcmVirtualContent - the children list is a list of target children
        if (sourceNode instanceof WcmVirtualContent) {
            sourceNode = sourceNode.target
        }

        requirePermissions(sourceNode, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        
        
        // @todo replace this with smarter queries on children instead of requiring loading of all child objects
        def typeRestriction = getContentClassForType(args.type)
        if (log.debugEnabled) {
            log.debug "Finding children of ${sourceNode.absoluteURI} restricting type to ${typeRestriction}"
        }
        def children = doCriteria(typeRestriction, args.status, args.params) {
            if (sourceNode == null) {
                isNull('parent')
            } else {
                eq('parent', sourceNode)
            }
            cache true
        }
        
        return children
    }


    /**
     * Sort function for queries that cannot be sorted in the database, i.e. aggregated data
     */
    def sortNodes(nodes, sortProperty, sortDirection = "asc") {
        if (sortProperty) {
            if (sortDirection == "asc") {
                return nodes.sort { a, b -> 
                    if (a[sortProperty]) 
                        return a[sortProperty].compareTo(b[sortProperty]) 
                    else 
                        return -1 
                }
            } else if (sortDirection == "desc") {
                return nodes.sort { a, b -> 
                    if (b[sortProperty]) 
                        return b[sortProperty].compareTo(a[sortProperty]) 
                    else 
                        return +1 
                }
            } else throw new IllegalArgumentException("Sort order must be one of [asc] or [desc], was: [$sortDirection]")
        } else return nodes
    }
    
    /**
     * Returns true if the node has a status that matches the supplied status
     * See findWithStatus() for rules and possible values of "status"
     */
    boolean contentMatchesStatus(status, WcmContent node) {
        if (status == null) {
            return true
        } else if (status == WcmContentRepositoryService.STATUS_ANY_PUBLISHED) {
            return WcmStatus.findAllByPublicContent(true).find { it == node.status }
        } else if (status instanceof Collection) {
            // NOTE: This assumes collection is a collection of codes, not WcmStatus objects
            return status.find { it == node.status.code }  
        } else if (status instanceof WcmStatus) {
            return node.status == status
        } else if (status instanceof Integer) {
            return node.status.code == status.code
        } else if (status instanceof IntRange) {
            return status.containsWithBounds(node.status.code)
        } else {
            def s = status.toString()
            if (s.isInteger()) {
                return s.toNumber() == node.status.code
            } else throw new IllegalArgumentException(
                "The [status] argument must be null (for 'any'), or '${WcmContentRepositoryService.STATUS_ANY_PUBLISHED}', an integer (or integer string), a collection of codes (numbers), a Status instance or an IntRange. You supplied [$status]")
        }        
    }
    
    /**
     * Find all the parents of the specified node, within the content hierarchy, optionally filtering by status and a content type class
     * @todo we can probably improve performance by applying the typeRestriction using some HQL
     */ 
    def findParents(WcmContent sourceNode, Map args = Collections.EMPTY_MAP) {
        requirePermissions(sourceNode, [WeceemSecurityPolicy.PERMISSION_VIEW])        

        // @todo change to criteria/select
        def references = (doCriteria(WcmVirtualContent, args.status, Collections.EMPTY_MAP) {
            eq('target', sourceNode) 
        })*.parent
         
        if (sourceNode.parent && contentMatchesStatus(args.status, sourceNode.parent)) {
            references << sourceNode.parent
        }
        // Allow null here if there's no restriction
        def typeRestriction = args.type ? getContentClassForType(args.type) : null
        def parents = []
        references?.unique()?.each { 
            if (typeRestriction ? typeRestriction.isAssignableFrom(it.class) : true) {
                parents << it
            }
        }
        return sortNodes(parents, args.params?.sort, args.params?.order)
    }

    /**
     * Locate a root node by uri, type, status and space
     */ 
    def findRootContentByURI(String aliasURI, WcmSpace space, Map args = Collections.EMPTY_MAP) {
        if (log.debugEnabled) {
            log.debug "findRootContentByURI: aliasURI $aliasURI, space ${space?.name}, args ${args}"
        }
        def r = doCriteria(getContentClassForType(args.type), args.status, args.params) {
            isNull('parent')
            eq('aliasURI', aliasURI)
            eq('space', space)
            maxResults(1)
            cache true
        }
        WcmContent node = r ? r[0] : null
        if (node) {
            requirePermissions(node, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        }
        return node
    }
    
    /**
     * find all root nodes by type and space
     */ 
    def findAllRootContent(WcmSpace space, Map args = Collections.EMPTY_MAP) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        if (log.debugEnabled) {
            log.debug "findAllRootContent $space, $args"
        }
        doCriteria(getContentClassForType(args.type), args.status, args.params) {
            isNull('parent')
            eq('space', space)
            cache true
        }
    }
    
    /**
     * find all nodes by type and space
     */ 
    def findAllContent(WcmSpace space, Map args = Collections.EMPTY_MAP) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        if (log.debugEnabled) {
            log.debug "findAllContent $space, $args"
        }
        doCriteria(getContentClassForType(args.type), args.status, args.params) {
            eq('space', space)
            cache true
        }
    }
    
    /**
     *
     * Find the content node that is identified by the specified uri path. This always finds a single WcmContent node
     * or none at all. Each node can have multiple URI paths, so this code returns the node AND the uri to its parent
     * so that you can tell where it is in the hierarchy
     *
     * This call does NOT filter by path.
     *
     * @param uriPath
     * @param space
     *
     * @return a map of 'content' (the node), 'lineage' (list of parent WcmContent nodes to reach the node)
     * and 'parentURI' (the uri to the parent of this instance of the node)
     */
    def findContentForPath(String uriPath, WcmSpace space, boolean useCache = true) {
        if (log.debugEnabled) {
            log.debug "findContentForPath uri: ${uriPath} space: ${space}"
        }

        def cacheKey = makeURICacheKey(space, uriPath)
        
        if (useCache) {
            // This looks up the uriPath in the cache to see if we can get a Map of the content id and parentURI
            // If we call getValue on the cache hit, we lose 50% of our performance. Just retrieving
            // the cache hit is not expensive.
            def cachedElement = uriToIdCache.get(cacheKey)
            def cachedContentInfo = cachedElement?.getValue()
            if (cachedContentInfo) {
                if (log.debugEnabled) {
                    log.debug "Found content info into cache for uri $uriPath: ${cachedContentInfo}"
                }
                // @todo will this break with different table mapping strategy eg multiple ids of "1" with separate tables?
                WcmContent c = WcmContent.get(cachedContentInfo.id)
                // @todo re-load the lineage objects here, currently they are ids!
                def reloadedLineage = cachedContentInfo.lineage?.collect { l_id ->
                    WcmContent.get(l_id)
                }
                if (log.debugEnabled) {
                    log.debug "Reconstituted lineage from cache for uri $uriPath: ${reloadedLineage}"
                }
                if (c) {
                    requirePermissions(c, [WeceemSecurityPolicy.PERMISSION_VIEW])        
                }
            
                return c ? [content:c, parentURI:cachedContentInfo.parentURI, lineage:reloadedLineage] : null
            }   
        }
        
        def tokens = uriPath.split('/')

        // @todo: optimize query 
        WcmContent content = findRootContentByURI(tokens[0], space)
        if (!content) content = findFileRootContentByURI(tokens[0], space)
        if (log.debugEnabled) {
            log.debug "findContentForPath $uriPath - root content node is $content"
        }
        
        def lineage = [content]
        if (content && (tokens.size() > 1)) {
            for (n in 1..tokens.size()-1) {
                def child = WcmContent.find("""from WcmContent c \
                        where c.parent = ? and c.aliasURI = ?""",
                        [content, tokens[n]])
                if (log.debugEnabled) {
                    log.debug "findContentForPath $uriPath - found child $child for path token ${tokens[n]}"
                }
                if (child) {
                    lineage << child
                    content = child
                } else {
                    // We hit a URI part that does not resolve to a content node
                    content = null
                    break
                }
            }
        }
    
        // Get all the URI parts except the last
        def parentURIParts = []
        if (tokens.size() > 1) {
            parentURIParts = tokens[0..tokens.size()-2]
        }

        def parentURI = parentURIParts.join('/')

        // Cache this resolution - found or not
        // This MUST be a new map otherwise we have immutability problems
        // Don't writer lineage if the result was null
        def cacheValue = [id:content?.id, 
          parentURI:parentURI, lineage: content ? (lineage.collect { l -> l.id }).toArray() : null]
        
        if (log.debugEnabled) {
            log.debug "Caching content info for uri $uriPath: $cacheValue"
        }
        if (useCache) {
            wcmCacheService.putToCache(uriToIdCache, cacheKey, cacheValue)
        }
        
        if (content) {
            requirePermissions(content, [WeceemSecurityPolicy.PERMISSION_VIEW])        

            [content:content, parentURI:parentURI, lineage:lineage]
        } else {
            return null
        }
    }
    
    def findFileRootContentByURI(String aliasURI, WcmSpace space, Map args = Collections.EMPTY_MAP) {
        if (log.debugEnabled) {
            log.debug "findFileRootContentByURI: aliasURI $aliasURI, space ${space?.name}, args ${args}"
        }
        def r = doCriteria(WcmContentFile, args.status, args.params) {
            eq('aliasURI', aliasURI)
            eq('space', space)
        }
        def res = r?.findAll(){it-> (it.parent == null) || !(it.parent instanceof WcmContentFile)}
        WcmContent result = res ? res[0] : null
        if (result) {
            requirePermissions(result, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        }
        return result
    }
    
    def getAncestors(uri, sourceNode) {
        // Can't impl this yet
    }
    
    def getTemplateForContent(def content){
        def template = (content.metaClass.hasProperty(content, 'template')) ? content.template : null
        if ((template == null) && (content.parent != null)){
            return getTemplateForContent(content.parent)
        }else{
            return template
        }
    }
    
    /**
     * Synchronize given space with file system
     * 
     * @param space - space to synchronize
    **/
    def synchronizeSpace(space) {
        requirePermissions(space, [WeceemSecurityPolicy.PERMISSION_ADMIN])        

        def existingFiles = new TreeSet()
        def createdContent = []
        def spaceDir = grailsApplication.parentContext.getResource(
                "${WcmContentFile.DEFAULT_UPLOAD_DIR}/${space.makeUploadName()}").file
        if (!spaceDir.exists()) spaceDir.mkdirs()
        spaceDir.eachFileRecurse {file ->
            def relativePath = file.absolutePath.substring(
                    spaceDir.absolutePath.size() + 1)
            def content = findContentForPath(relativePath, space, false)?.content
            //if content wasn't found then create new
            if (!content){
                createdContent += createContentFile("${spaceDir.name}/${relativePath}")
                content = findContentForPath(relativePath, space, false)?.content
                while (content){
                    existingFiles << content
                    content = content.parent
                }
            }else{
                existingFiles << content
            }
        }
        def allFiles = WcmContentFile.findAllBySpace(space);
        def missedFiles = allFiles.findAll(){f->
            !(f.id in existingFiles*.id)
        }
        
        return ["created": createdContent, "missed": missedFiles]
    }
    
    /**
     * Creates WcmContentFile/WcmContentDirectory from specified <code>path</code>
     * on the file system.
     *
     * @param path
     */
    def createContentFile(path) {
        if (log.debugEnabled) {
            log.debug "Creating content node for server file at [$path]"
        }
        
        List tokens = path.replace('\\', '/').split('/')
        if (tokens.size() > 1) {
            def space = WcmSpace.findByAliasURI((tokens[0] == WcmContentFile.EMPTY_ALIAS_URI) ? '' : tokens[0])
            def parents = tokens[1..(tokens.size() - 1)]
            def ancestor = null
            def content = null
            def createdContent = []
            parents.eachWithIndex(){ obj, i ->
                def parentPath = "${parents[0..i].join('/')}"
                def file = grailsApplication.parentContext.getResource(
                        "${WcmContentFile.DEFAULT_UPLOAD_DIR}/${space.makeUploadName()}/${parentPath}").file
                content = findContentForPath(parentPath, space)?.content
                if (!content){
                    if (file.isDirectory()){
                        content = new WcmContentDirectory(title: file.name,
                            content: '', filesCount: 0, space: space, orderIndex: 0,
                            mimeType: '', fileSize: 0, status: WcmStatus.findByPublicContent(true))
                    }else{
                        def mimeType = ServletContextHolder.servletContext.getMimeType(file.name)
                        content = new WcmContentFile(title: file.name,
                            content: '', space: space, orderIndex: 0, 
                            mimeType: (mimeType ? mimeType : ''), fileSize: file.length(),
                            status: WcmStatus.findByPublicContent(true))
                    }
                    // @todo this needs fixing, we don't know the parent yet!
                    content.createAliasURI(content.parent)

                    requirePermissions(content.parent ?: space, [WeceemSecurityPolicy.PERMISSION_CREATE])        

                    if (!content.save()) {
                        log.error "Failed to save content ${content} - errors: ${content.errors}"
                        assert false
                    } else {
                        createdContent << content
                    }
                }
                if (ancestor){
                    if (ancestor.children == null) ancestor.children = new TreeSet()
                    ancestor.children << content
                    if (ancestor instanceof WcmContentDirectory)
                        ancestor.filesCount += 1
                    if (log.debugEnabled) {
                        log.debug "Updated parent node of new file node [${ancestor.dump()}]"
                    }
                    assert ancestor.save(flush: true)
                    content.parent = ancestor
                    if (log.debugEnabled) {
                        log.debug "Saving content node of new file node [${content.dump()}]"
                    }
                    if (log.debugEnabled && !content.validate()) {
                        log.debug "Saving content node of new file node is about to fail. Node: [${content.dump()}], Errors: [${content.errors}]"
                    }
                    assert content.save(flush: true)
                }
                ancestor = content
            }
            return createdContent
        }
        return null
    }
    
    WcmContent createUserSubmittedContent(space, parent, type, data, request) throws AccessDeniedException {
        if (!(space instanceof WcmSpace)) {
            space = WcmSpace.get(space.toLong())
        }
        assert space
        if (parent) {
            if (!(parent instanceof WcmContent)) {
                parent = WcmContent.get(parent.toLong())
            }
        } else {
            parent = null
        }

        // Need to get the status before we query!
        def stat = WcmStatus.listOrderByCode(max:1, order:'asc')[0]
        
        Class contentClass = getContentClassForType(type)
        // check CREATE permission on the uri & user
        def n = parent ?: space
        requirePermissions(n, [WeceemSecurityPolicy.PERMISSION_CREATE], contentClass)
        // create content and populate

        // Prevent setting status and other internal values
        def publicProperties = contentClass.publicSubmitProperties 
        def dataKeys = data.collect { k, v -> k }
        // We eliminate all properties that are not in this list, so status etc cannot be set
        if (publicProperties) {
            dataKeys.each { k ->
                if (!publicProperties.contains(k)) {
                    data.remove(k)
                }
            }
        }
        
        // Now create it
        def newContent = createNode(type, data) { c ->
            c.space = space
            c.parent = parent
            c.status = stat
            // We should convention-ize this so they can have different fields
            c.ipAddress = request.remoteAddr
        }
        // Check for binding errors
        return newContent.hasErrors() ? newContent : newContent.save() // it might not work, but hasErrors will be set if not
    }
    
    /**
     * Return a list of month/year pairs for all months where there is content under the parent (children) of the specified type
     * Results are in descending year and month order
     * @todo Implement permissions use here, or is it ok to ignore this, it only leaks dates?
     */
    def findMonthsWithContent(parentOrSpace, contentType) {
        def type = getContentClassForType(contentType)
        def parentClause = parentOrSpace instanceof WcmContent ? "parent = :parent" : "space = :parent"
        def monthsYears = type.executeQuery("""select distinct month(publicationDate), year(publicationDate) from 
${type.name} where $parentClause and status.publicContent = true and publicationDate < current_timestamp() 
order by year(publicationDate) desc, month(publicationDate) desc""", [parent:parentOrSpace])
        return monthsYears?.collect() {
            [month: it[0], year: it[1]]
        }      
    }
        
    /** 
     * Get all the content within a given time period, inclusive at both ends
     * 
     * 
     */
    def findContentForTimePeriod(parentOrSpace, startDate, endDate, args = [:]) {
        if (log.debugEnabled) {
            log.debug "Finding children of ${parentOrSpace} with args $args"
        }
        assert parentOrSpace != null

        // for WcmVirtualContent - the children list is a list of target children
        if (parentOrSpace instanceof WcmVirtualContent) {
            parentOrSpace = parentOrSpace.target
        }

        requirePermissions(parentOrSpace, [WeceemSecurityPolicy.PERMISSION_VIEW])        
        
        // @todo replace this with smarter queries on children instead of requiring loading of all child objects
        def typeRestriction = getContentClassForType(args.type)
        if (log.debugEnabled) {
            log.debug "Finding children of ${parentOrSpace} restricting type to ${typeRestriction}"
        }
        def children = doCriteria(typeRestriction, args.status, args.params) {
            if (parentOrSpace instanceof WcmSpace) {
                isNull('parent')
                eq('space', parentOrSpace)
            } else {
                eq('parent', parentOrSpace)
            }

            ge('publicationDate', startDate)
            le('publicationDate', endDate)
            order('publicationDate', 'desc')
            cache true
        }
        
        return children
    }
    
    /**
     * Calculates start and end timestamps for first and last second in given month and year, 
     * returning object containing:
     * start - Date of 00:00:00 at start of month
     * end - Date of 23:59:59 at end of month
     * @param month Number in range 1-12
     * @param year Number 
     */
    def calculateMonthStartEndDates(month, year) {
        def t = new GregorianCalendar()
        t.set(Calendar.DAY_OF_MONTH, 1)
        t.set(Calendar.MILLISECOND, 0)
        t.set(Calendar.HOUR_OF_DAY, 0)
        t.set(Calendar.MINUTE, 0)
        t.set(Calendar.SECOND, 0)
        t.set(Calendar.MONTH, month-1)
        t.set(Calendar.YEAR, year)
        
        def result = [:]
        result.start = t.time
        
        t.set(Calendar.DAY_OF_MONTH, t.getActualMaximum(Calendar.DAY_OF_MONTH))
        t.set(Calendar.HOUR_OF_DAY, t.getActualMaximum(Calendar.HOUR_OF_DAY))
        t.set(Calendar.MINUTE, t.getActualMaximum(Calendar.MINUTE))
        t.set(Calendar.SECOND, t.getActualMaximum(Calendar.SECOND))
        t.set(Calendar.MILLISECOND, t.getActualMaximum(Calendar.MILLISECOND))
        result.end = t.time
        
        return result
    }
    
    /**
     * Calculates start and end timestamps for first and last second in given day, month and year, 
     * returning object containing:
     * start - Date of 00:00:00 at start of the day
     * end - Date of 23:59:59 at end of the day
     * @param day Number in range 1-31
     * @param month Number in range 1-12
     * @param year Number 
     */
    def calculateDayStartEndDates(day, month, year) {
        def t = new GregorianCalendar()
        t.set(Calendar.DAY_OF_MONTH, day)
        t.set(Calendar.MILLISECOND, 0)
        t.set(Calendar.HOUR_OF_DAY, 0)
        t.set(Calendar.MINUTE, 0)
        t.set(Calendar.SECOND, 0)
        t.set(Calendar.MONTH, month-1)
        t.set(Calendar.YEAR, year)
        
        def result = [:]
        result.start = t.time
        
        t.set(Calendar.HOUR_OF_DAY, t.getActualMaximum(Calendar.HOUR_OF_DAY))
        t.set(Calendar.MINUTE, t.getActualMaximum(Calendar.MINUTE))
        t.set(Calendar.SECOND, t.getActualMaximum(Calendar.SECOND))
        t.set(Calendar.MILLISECOND, t.getActualMaximum(Calendar.MILLISECOND))
        result.end = t.time
        
        return result
    }
    
    /**
     * Get an instace of the Groovy Script object defined by the WcmScript content node.
     * Uses caching of compiled classes to prevent permgen explosion, and unique classloaders
     */ 
    Script getScriptInstance(WcmScript s) {
        def absURI = s.absoluteURI
        if (log.debugEnabled) {
            log.debug "Getting Groovy script class for $absURI"
        }
        
        def cls = wcmCacheService.getOrPutObject(CACHE_NAME_GSP_CACHE, makeURICacheKey(s.space, absURI)) {
            if (log.debugEnabled) {
                log.debug "Compiling Groovy script class for $absURI"
            }
            def code = s.content
            def cls = new GroovyClassLoader().parseClass(code)
            assert Script.isAssignableFrom(cls)
            return cls
        }
        cls.newInstance()
    }
    
    /**
     * Look for any content pending publication, and move statys to published
     */
    def publishPendingContent() {
        def now = new Date()
        // Find all content with publication date less than now
        def pendingContent = WcmContent.withCriteria {
            isNotNull('publicationDate')
            lt('publicationDate', now)
            status {
                eq('publicContent', false)
            }
        }
        def count = 0
        pendingContent?.each { content ->
            // Find the next status (in code order) that is public content, after the content's current status
            content.status = WcmStatus.findByPublicContentAndCodeGreaterThan(true, content.status.code)
            count++
        }
        return count
    }
    
    def searchForContent(String query, WcmSpace space,  contentOrPath = null, args = null) {
        def baseURI
        if (contentOrPath) {
            if (contentOrPath instanceof WcmContent) {
                baseURI = contentOrPath.absoluteURI
            } else {
                baseURI = contentOrPath.toString()
            }
        }
        WcmContent.search([reload:true, offset:args?.offset ?:0, max:args?.max ?: 25]){
            queryString(query)

/* This doesn't work yet
            // Restrict to base URI
            if (baseURI) {
                must {
                    term('absoluteURI', baseURI+'/')
                }
            }
*/
            
            // Restrict to space
            must {
                listContentClassNames().each { n ->
                    def t = '$/'+n.replaceAll('\\.', '_')+'/space/id'
                    term(t, space.id)
                }
            }
        }
    }

    def searchForPublicContent(String query, WcmSpace space, contentOrPath = null, args = null) {
        WcmContent.search([reload:true, offset:args?.offset ?:0, max:args?.max ?: 25]){
            must(queryString(query))

            // Restrict to public
            must(term('status_publicContent', true))

            // Restrict to space
            must {
                listContentClassNames( { 
                    def hasSCProp = it.metaClass.hasProperty(it.class, 'standaloneContent')
                    !hasSCProp || it.standaloneContent
                } ).each { n ->
                    def t = '$/'+n.replaceAll('\\.', '_')+'/space/id'
                    term(t, space.id)
                }
            }
        }
    }
}
