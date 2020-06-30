/**
 * 
 */
package au.org.arcs.shibext.sharedtoken;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.ldaptive.AttributeModification;
import org.ldaptive.AttributeModificationType;
import org.ldaptive.Connection;
import org.ldaptive.LdapAttribute;
import org.ldaptive.LdapEntry;
import org.ldaptive.ModifyRequest;
import org.ldaptive.Response;
import org.ldaptive.ResultCode;
import org.ldaptive.SearchResult;
import org.ldaptive.provider.ProviderConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.shibboleth.idp.attribute.resolver.AbstractDataConnector;
import net.shibboleth.idp.attribute.resolver.ResolvedAttributeDefinition;
import net.shibboleth.idp.attribute.resolver.ResolvedDataConnector;
import net.shibboleth.idp.attribute.resolver.context.AttributeResolutionContext;
import net.shibboleth.idp.attribute.resolver.context.AttributeResolverWorkContext;
import net.shibboleth.idp.attribute.resolver.dc.ldap.impl.ExecutableSearchFilter;
import net.shibboleth.idp.attribute.resolver.dc.ldap.impl.LDAPDataConnector;
import net.shibboleth.idp.attribute.resolver.ResolutionException;
import net.shibboleth.idp.attribute.IdPAttribute;
import net.shibboleth.idp.attribute.IdPAttributeValue;
import net.shibboleth.idp.attribute.StringAttributeValue;
import net.shibboleth.idp.attribute.resolver.ResolverAttributeDefinitionDependency;
import net.shibboleth.idp.attribute.resolver.ResolverDataConnectorDependency;
import net.shibboleth.utilities.java.support.collection.LazyMap;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;

/**
 * @author Damien Chen
 * @author Vlad Mencl
 * 
 */
public class SharedTokenDataConnector extends AbstractDataConnector {

	/** Class logger. */
	private final Logger log = LoggerFactory
			.getLogger(SharedTokenDataConnector.class);

	/** Minimum salt length */
	public static final int MINIMUM_SALT_LENGTH = 16;	
	
	private static String SEPARATOR = ",";

	/** ID of the attribute generated by this data connector. */
	private String generatedAttributeId;

	/**
	 * IdP identifier used when computing the sharedToken.
	 */
	private String idpIdentifier;

	/**
	 * ID of the attribute whose first value is used when generating the
	 * computed ID.
	 */
	private String sourceAttributeId;

	/** Salt used when computing the ID. */
	private byte[] salt;

	/** Whether to store the sharedToken to Ldap */
	private boolean storeLdap;

	/** ID of the LDAPDataConnector to use if storing values in LDAP */
	private String ldapConnectorId;

	private String storedAttributeName = "auEduPersonSharedToken";
	
	/** Whether to store the sharedToken to database */
	private boolean storeDatabase;

	/** SharedToken data store. */
	private SharedTokenStore stStore;

	/** Primary key in SharedToken database */
	private String primaryKeyName;

	public SharedTokenDataConnector() {
		super();
		log.debug("construct empty SharedTokenDataConnector ...");
	}

	/**
	 * Constructor.
	 * 
	 * @param generatedAttributeId
	 *            ID of the attribute generated by this data connector
	 * @param sourceAttributeId
	 *            ID of the attribute whose first value is used when generating
	 *            the computed ID
	 * @param idSalt
	 *            salt used when computing the ID
	 * @param idpIdentifier
	 *            the IdP identifier to use.  If not set, defaults to resolutionContext.getAttributeIssuerID() at runtime.
	 * @param storeLdap
	 *            Whether to store the sharedToken values into Ldap
	 * @param storeDatabase
	 *            Whether to store the sharedToken values into a database
	 * @param source
	 *            dataSource to use for retrieving and storing sharedToken values
	 * @param primaryKeyName
	 *            primary key name to use in the database.
	 */
	public SharedTokenDataConnector(String generatedAttributeId,
			String sourceAttributeId, byte[] idSalt, 
			String idpIdentifier, boolean storeLdap,
			boolean storeDatabase, DataSource source, String primaryKeyName) {

		try {
			log.debug("construct SharedTokenDataConnector ...");
			setGeneratedAttributeId(generatedAttributeId);
			setSourceAttributeId(sourceAttributeId);
			setSalt(idSalt);
			setIdpIdentifier(idpIdentifier);
			setStoreLdap(storeLdap);
			setPrimaryKeyName(primaryKeyName);
			setStoreDatabase(storeDatabase);

			if (storeDatabase) {
				setDataSource(source);
			}

		} catch (Exception e) {
			// catch any exception so that the IdP will not screw up.
                        log.error("Failed to construct SharedTokenDataConnector object", e);
		}

	}

	/** Initialize the connector - check all required properties have been set
	 * @see net.shibboleth.idp.attribute.resolver.AbstractDataConnector#doInitialize()
	 */
	@Override
	protected void doInitialize() throws ComponentInitializationException {
		super.doInitialize();
		log.debug("Initialize called on SharedTokenDataConnector {}", getId());
		
		if (MiscHelper.isEmpty(sourceAttributeId)) 
			throw new ComponentInitializationException(
					"Source attribute ID must be set and not empty");
		
		if (MiscHelper.isEmpty(generatedAttributeId)) 
			throw new ComponentInitializationException(
					"Generated attribute ID must be set and not empty");
		
		if (storeDatabase) {
			if (stStore == null) {
				throw new ComponentInitializationException("SharedToken ID " + getId()
						+ " data connector requires a Database Connection when storeDatabase=true");
			}
		} else {
		
			// check ldapConnectorId is set whenenver storeDatabase is false
			// - because then we are already fetching from LDAP
			// (and we now need the connector Id even for fetching)
			if (getLdapConnectorId() == null) {
				throw new ComponentInitializationException("SharedToken ID " + getId()
						+ " data connector requires an Ldap Connector ID when using sharedToken from LDAP");
			}
		
			// whenever we are using LDAP for reading or writing, we need to see the LDAP connector among dependencies

			// check getLdapConnectorId() can be found in the getDependencies() Set
			if (!dependenciesContainsId(getAttributeDependencies(), getDataConnectorDependencies(), getLdapConnectorId())) {
				throw new ComponentInitializationException("SharedToken ID " + getId()
						+ " is configured to use LDAP connector ID " + getLdapConnectorId()
						+ " but the connector is not listed in dependencies");
			}
		}
		
		// log a warning if both storeLdap=false and storeDatabase=false
		if (!storeDatabase && !getStoreLdap()) {
			log.warn("SharedTokenDataConnector {} is configured to store values neither in database nor in LDAP.  SharedToken values generated on the fly SHOULD NOT be used on production systems.", getId());
		}
		
		// also log a warning if both are true
		if (storeDatabase && getStoreLdap()) {
			log.warn("SharedTokenDataConnector {} is configured to store values both in database and in LDAP.  The database setting have higher precedence and LDAP will NOT be consulted for sharedToken values.", getId());
		}
		
		// log a warning if any of the attributes listed in getSourceAttributeId() cannot be found in the getDependencies() Set
		String[] ids = getSourceAttributeId().split(SEPARATOR);
		for (int i = 0; i < ids.length; i++) {
			if (!dependenciesContainsId(getAttributeDependencies(), getDataConnectorDependencies(), ids[i])) {
				log.warn("Source attribute ID {} not listed in dependencies of connector {}", ids[i], getId());
			}			
		}

	}
	
	/** Resolve the shared token value to be provided by this connector
	 *
	 * 
	 * @see
	 * edu.internet2.middleware.shibboleth.common.attribute.resolver.provider
	 * .ResolutionPlugIn
	 * #resolve(edu.internet2.middleware.shibboleth.common.attribute
	 * .resolver.provider.AttributeResolutionContext)
	 */
	/** {@inheritDoc} */

	@Override
	protected Map<String, IdPAttribute> doDataConnectorResolve(
			AttributeResolutionContext resolutionContext, AttributeResolverWorkContext resolverWorkContext)
			throws ResolutionException {

		log.debug("starting SharedTokenDataConnector.resolve( ) ...");

		Map<String, IdPAttribute> attributes = new LazyMap<String, IdPAttribute>();

		String sharedToken = null;
		try {
			if (storeDatabase) {
				log.debug("storeDatabase = true. Try to get SharedToken from database");
				// Collection<Object> colUid =
				// super.getValuesFromAllDependencies(
				// resolutionContext, PRIMARY_KEY);

				// String uid = (String) colUid.iterator().next();

				String uid = resolutionContext.getPrincipal();

				if (stStore != null) {
					sharedToken = stStore.getSharedToken(uid, primaryKeyName);
				} else {
					log.error("SharedTokenStore is null");
					throw new IMASTException("SharedTokenStore is null");
				}
				if (sharedToken == null) {
					log.debug("sharedToken does not exist, will generate a new one and store in database.");
					sharedToken = getSharedToken(resolutionContext, resolverWorkContext);
					stStore.storeSharedToken(uid, sharedToken, primaryKeyName);
				} else {
					log.debug("sharedToken exists, will not generate a new one.");
				}
			} else {
				log.debug("storeDatabase = false. Try to get SharedToken from LDAP.");

				if (log.isTraceEnabled()) {
					// DEBUG: dump list of visible attributes
					Map <String, ResolvedAttributeDefinition> debugAttrs = resolverWorkContext.getResolvedIdPAttributeDefinitions(); 
					for (Iterator<String> itAttr = debugAttrs.keySet().iterator(); itAttr.hasNext(); ) {
						String key = itAttr.next();
						log.trace("resolverWorkContext.getResolvedIdPAttributeDefinitions() contains {}", key);
						IdPAttribute debugIdPAttr = debugAttrs.get(key).getResolvedAttribute();
						for (Iterator<IdPAttributeValue<?>> itValue = debugIdPAttr.getValues().iterator(); itValue.hasNext(); ) {
							log.trace("attribute {} contains value {}", key, itValue.next().getValue().toString());						
						}
					}
					
					// DEBUG: iterate over all resolved connectors and dump their resolved attributes and values
					Map <String, ResolvedDataConnector> resolvedDataConnectors = resolverWorkContext.getResolvedDataConnectors();
					for (Iterator<String> dcIt = resolvedDataConnectors.keySet().iterator(); dcIt.hasNext(); ) {
						String dcId = dcIt.next();
						log.trace("resolverWorkContext.getResolvedDataConnectors() contains {}", dcId);
						Map <String, IdPAttribute> dcAttrs = resolvedDataConnectors.get(dcId).getResolvedAttributes(); 
						for (Iterator<String> itAttr = dcAttrs.keySet().iterator(); itAttr.hasNext(); ) {
							String key = itAttr.next();
							log.trace("resolved DC {} contains attribute {}", dcId, key);
							IdPAttribute dcIdPAttr = dcAttrs.get(key);
							for (Iterator<IdPAttributeValue<?>> itValue = dcIdPAttr.getValues().iterator(); itValue.hasNext(); ) {
								log.trace("resolved DC {} attribute {} contains value {}", dcId, key, itValue.next().getValue().toString());						
							}
						}
					}
				}
				
				// We cannot rely on just getting storedAttributeName from
				//   resolverWorkContext.getResolvedIdPAttributeDefinitions()
				// - because there won't be an attribute definition of the same name to resolve from LDAP.
				// (Such a definition would have an ID clashing with the attribute definition done using this connector.)
				// Yes, we could import the attribute from LDAP explicitly under a different name
				// And then pass that name to this connector as an additional parameter.
				// But for now, let's get storedAttribteName as a ResolvedAttribute from the sharedTokenDC directly.
				ResolvedDataConnector ldapDc = resolverWorkContext.getResolvedDataConnectors().get(getLdapConnectorId());
				IdPAttribute sharedTokenFromLDAP = ldapDc.getResolvedAttributes().get(storedAttributeName);
				
				if (sharedTokenFromLDAP==null || sharedTokenFromLDAP.getValues().size() < 1) {
					log.debug("sharedToken does not exist, will generate a new one.");
					sharedToken = getSharedToken(resolutionContext, resolverWorkContext);
					if (getStoreLdap()) {
						log.debug("storeLdap=true, will store the SharedToken in LDAP.");
						storeSharedTokenInLdap(resolutionContext, resolverWorkContext, sharedToken);
					} else
						log.debug("storeLdap=false, not to store sharedToken in Ldap");
				} else {
					log.debug("sharedToken  exists, will not to generate a new one.");
					sharedToken = sharedTokenFromLDAP.getValues().get(0).getValue().toString();
				}
			}
		} catch (Exception e) {
			// catch any exception so that the IdP will not screw up.
			log.error("Failed to resolve sharedToken", e);

			// however, if we encountered an error (possibly in saving the attribute value),
			// do not pass the value out - as the error would get masked and overlooked
			if (sharedToken != null) {
				log.error("Discarding sharedToken value {} due to errors encountered: {}", sharedToken, e.getMessage());
				sharedToken = null;
			}
		}
		if (sharedToken != null) {
			IdPAttribute attribute = new IdPAttribute(getGeneratedAttributeId());
			Collection<IdPAttributeValue<String>> values = new ArrayList<IdPAttributeValue<String>>();
			values.add(new StringAttributeValue(sharedToken));
			attribute.setValues(values);
			attributes.put(attribute.getId(), attribute);			
		}
		return attributes;
	}

	/**
	 * Gets the sharedToken.
	 * 
	 * @param resolutionContext
	 *            current resolution context
	 * 
	 * @return sharedToken
	 * 
	 * @throws ResolutionException
	 *             thrown if there is a problem retrieving or storing the
	 *             persistent ID
	 */
	private String getSharedToken(AttributeResolutionContext resolutionContext, AttributeResolverWorkContext resolverWorkContext)
			throws ResolutionException {

		String localId = getLocalId(resolutionContext, resolverWorkContext);
		String persistentId = this.createSharedToken(resolutionContext,
				localId, salt);
		return persistentId;

	}

	/**
	 * Store the sharedToken in LDAP.
	 * 
	 * @param resolutionContext
	 *            current resolution context
	 * @param sharedToken
	 * 
	 */

	private void storeSharedTokenInLdap(
			AttributeResolutionContext resolutionContext, AttributeResolverWorkContext resolverWorkContext, String sharedToken)
			throws IMASTException {

		log.debug("storing sharedToken value {} in LDAP connector {}", sharedToken, getLdapConnectorId());

		try {		
			// store the sharedToken value in LDAP, using the configured data connector
					
			// get the LDAP connector
			ResolvedDataConnector ldapDcResolved = resolverWorkContext.getResolvedDataConnectors().get(getLdapConnectorId());
			if (ldapDcResolved == null) {
			    log.error("LDAPDataConnector {} not found in resolverWorkContext.getResolvedDataConnectors()", getLdapConnectorId());
			    throw new IMASTException("LDAPDataConnector "+getLdapConnectorId()+" not found in resolverWorkContext.getResolvedDataConnectors()");
			}
			LDAPDataConnector ldapDc = (LDAPDataConnector)ldapDcResolved.getResolvedConnector();
			
			// We need to construct a map of resolved attribute values in order to construct a search filter.  
					
			// uh, can we get this structure easier or do we need to build it?
			Map<String, List<IdPAttributeValue<?>>> resolvedAttributeValues = new TreeMap<String,List<IdPAttributeValue<?>>>();
			Map<String, IdPAttribute> resolvedAttributes = resolutionContext.getResolvedIdPAttributes(); 
			for (Iterator<String> itAttr = resolvedAttributes.keySet().iterator(); itAttr.hasNext(); ) {
				String attrKey = itAttr.next();						
			    resolvedAttributeValues.put(attrKey, resolvedAttributes.get(attrKey).getValues());
			}
			
			// now we can construct a search filter 
			ExecutableSearchFilter sf = ldapDc.getExecutableSearchBuilder().build(resolutionContext, resolvedAttributeValues);
			SearchResult sr = sf.execute(ldapDc.getSearchExecutor(), ldapDc.getConnectionFactory());
			if ( sr.size() == 0 ) throw new IMASTException("No search results found - cannot store sharedToken");
			String targetDn = null;
			for (Iterator<LdapEntry> itSr = sr.getEntries().iterator(); itSr.hasNext(); ) {
				LdapEntry srEntry = itSr.next();
				log.debug("Search Result Entry DN is {}", srEntry.getDn());
				targetDn = srEntry.getDn();
		    }
			if ( sr.size() > 1 ) {
				log.warn("Multiple search results found, only last one will be updated ({})", targetDn);
			}
			
			// now construct a Modify operation
			ModifyRequest mr = new ModifyRequest(targetDn, 
					new AttributeModification(AttributeModificationType.ADD, 
							new LdapAttribute(storedAttributeName, sharedToken)));

			log.info("adding {}:{} to {}:{}", storedAttributeName, sharedToken,
					getLdapConnectorId(), targetDn);			

			// and get a connection and apply the modify operation
			Connection ldapConn = ldapDc.getConnectionFactory().getConnection();
			checkLdapResponse(ldapConn.open());
			ProviderConnection conn = ldapConn.getProviderConnection();
			checkLdapResponse(conn.modify(mr));
			ldapConn.close();

		} catch (Exception e) {
			// catch any exception, the program will go on.
			log.error("Failed to store sharedToken into LDAP", e);
			throw new IMASTException("Failed to save attribute into ldap entry", e);

		}
	}

	private void checkLdapResponse(Response<Void> ldapResponse) throws IMASTException {
		if (ldapResponse.getResultCode()!=ResultCode.SUCCESS)
			throw new IMASTException("LDAP response was not SUCCESS but " + ldapResponse.getResultCode().toString() + " " + ldapResponse.getMessage());
	}
	
	/**
	 * Returns a printable form of a local ID.
	 * 
	 * If the local ID is just alphanumeric, return the local ID.
	 * 
	 * Otherwise, return the local ID base 64 encoded.
	 * 
	 * This is a workaround for AD setups where local ID would be 
	 * based on objectGUID - which is binary but the LDAP configuration
	 * not always renders it as such - and would then be putting binary 
	 * data into the log file. 
	 */
	private String printableLocalId(String localId) {
		if (Pattern.matches("^[a-zA-Z0-9@\\\\]+$", localId))
			return localId;	
		else
			return Base64.encodeBase64String(localId.getBytes());
	}

	/**
	 * Creates the sharedToken that is unique and persistent within a federation
	 * 
	 * @param resolutionContext
	 *            current resolution context
	 * @param localId
	 *            principal the the persistent ID represents, might be a
	 *            combination of attributes, e.g. uid+mail.
	 * @param salt
	 *            salt used when computing a persistent ID via SHA-1 hash
	 * 
	 * @return the created identifier
	 * 
	 * @throws ResolutionException
	 *             thrown if there is a problem
	 */
	private String createSharedToken(
			AttributeResolutionContext resolutionContext, String localId,
			byte[] salt) throws ResolutionException {
		String persistentId;
		log.debug("creating a sharedToken ...");
		try {
			String localEntityId = null;
			if (this.idpIdentifier == null) {
				localEntityId = resolutionContext.getAttributeIssuerID();
			} else {
				localEntityId = idpIdentifier;
			}
			String globalUniqueID = localId + localEntityId + new String(salt);
			log.debug("the globalUniqueID (user/idp/salt): " + printableLocalId(localId) + " / "
					+ localEntityId + " / " + new String(salt));
			byte[] hashValue = DigestUtils.sha1(globalUniqueID);
			byte[] encodedValue = Base64.encodeBase64(hashValue);
			persistentId = new String(encodedValue);
			persistentId = this.replace(persistentId);
			log.debug("the created sharedToken: " + persistentId);
			if (log.isInfoEnabled()) {
			    log.info("Created a new shared token value {} for localId {}", persistentId, printableLocalId(localId));
			}
			
		} catch (Exception e) {
			log.error("Failed to create the sharedToken", e);
			throw new ResolutionException("Failed to create the sharedToken", e);
		}
		return persistentId;

	}

	private String replace(String persistentId) {
		// begin = convert non-alphanum chars in base64 to alphanum
		// (/+=)
		log.debug("converting Base64 shared token to Url-safe Base64");
		if (persistentId.contains("/") || persistentId.contains("+")
				|| persistentId.contains("=")) {
			String aepst;
			if (persistentId.contains("/")) {
				aepst = persistentId.replaceAll("/", "_");
				persistentId = aepst;
			}

			if (persistentId.contains("+")) {
				aepst = persistentId.replaceAll("\\+", "-");
				persistentId = aepst;
			}

			if (persistentId.contains("=")) {
				aepst = persistentId.replaceAll("=", "");
				persistentId = aepst;
			}
		}

		return persistentId;
	}

	/**
	 * Gets the local ID component of the persistent ID.
	 * 
	 * @param resolutionContext
	 *            current resolution context
	 * 
	 * @return local ID component of the persistent ID
	 * 
	 * @throws ResolutionException
	 *             thrown if there is a problem resolving the local id
	 */
	private String getLocalId(AttributeResolutionContext resolutionContext, AttributeResolverWorkContext resolverWorkContext)
			throws ResolutionException {

		log.debug("gets local ID ...");

		String[] ids = getSourceAttributeId().split(SEPARATOR);
		// get list of already resolved attributes (from dependencies)
		Map <String,ResolvedAttributeDefinition> resolvedAttributesMap = 
				resolverWorkContext.getResolvedIdPAttributeDefinitions();	

		StringBuffer localIdValue = new StringBuffer();
		for (int i = 0; i < ids.length; i++) {
			Collection<IdPAttributeValue<?>> sourceIdValues = null;
			
			if (resolvedAttributesMap.get(ids[i]) != null ) 
				sourceIdValues = resolvedAttributesMap.get(ids[i]).getResolvedAttribute().getValues();

			if (sourceIdValues == null || sourceIdValues.isEmpty()) {
				log.error("Source attribute {} for connector {} provide no values",
						ids[i], getId());
				throw new ResolutionException("Source attribute "
						+ ids[i] + " for connector " + getId()
						+ " provided no values");
			}

			if (sourceIdValues.size() > 1) {
				log.warn("Source attribute {} for connector {} has more than one value, only the first value is used",
						ids[i], getId());
			}
			localIdValue.append(sourceIdValues.iterator().next().getValue().toString());
		}
		log.debug("local ID: " + printableLocalId(localIdValue.toString()));

		return localIdValue.toString();
	}
	
	private boolean dependenciesContainsId(Set<ResolverAttributeDefinitionDependency> attrDependencies,
                Set<ResolverDataConnectorDependency> dcDependencies, String id) {

            for (Iterator<ResolverAttributeDefinitionDependency> it=attrDependencies.iterator(); it.hasNext(); ) {
                    if (it.next().getDependencyPluginId().equals(id)) return true;
            }
            for (Iterator<ResolverDataConnectorDependency> it=dcDependencies.iterator(); it.hasNext(); ) {
                    ResolverDataConnectorDependency dc = it.next();
                    if (dc.getDependencyPluginId().equals(id)) return true;

                    // accept also a name of an attribute on a DataConnector dependency
                    for (Iterator<String> it2 = dc.getAttributeNames().iterator(); it2.hasNext(); ) {
                        if (it2.next().equals(id)) return true;
                    }
            }
            return false;
	}

	/**
	 * Gets the salt used when computing the ID.
	 * 
	 * @return salt used when computing the ID
	 */
	public byte[] getSalt() {
		return salt;
	}

	/**
	 * Gets the ID of the attribute whose first value is used when generating
	 * the computed ID.
	 * 
	 * @return ID of the attribute whose first value is used when generating the
	 *         computed ID
	 */
	public String getSourceAttributeId() {
		return sourceAttributeId;
	}

	public void setSourceAttributeId(String sourceAttributeId) {
		if (MiscHelper.isEmpty(sourceAttributeId)) {
			throw new IllegalArgumentException(
					"Provided source attribute ID must not be empty");
		}
		this.sourceAttributeId = sourceAttributeId;
	}

	/**
	 * Gets the ID of the attribute generated by this connector.
	 * 
	 * @return ID of the attribute generated by this connector
	 */
	public String getGeneratedAttributeId() {
		return generatedAttributeId;
	}

	/**
	 * @return the storeLdap
	 */
	public boolean getStoreLdap() {
		return storeLdap;
	}

	/**
	 * @return the idpIdentifier
	 */
	public String getIdpIdentifier() {
		return idpIdentifier;
	}

	/**
	 * @param idpIdentifier
	 *            the idpIdentifier to set
	 */
	public void setIdpIdentifier(String idpIdentifier) {
		this.idpIdentifier = idpIdentifier;
	}

	public void setGeneratedAttributeId(String generatedAttributeId) {
		if (MiscHelper.isEmpty(generatedAttributeId)) {
			throw new IllegalArgumentException(
					"Provided generated attribute ID must not be empty");
		}
		this.generatedAttributeId = generatedAttributeId;
	}

	public boolean isStoreDatabase() {
		return storeDatabase;
	}

	public void setStoreDatabase(boolean storeDatabase) {
		this.storeDatabase = storeDatabase;
	}

	public String getPrimaryKeyName() {
		return primaryKeyName;
	}

	public void setPrimaryKeyName(String primaryKeyName) {
		this.primaryKeyName = primaryKeyName;
	}

	public void setSalt(byte[] salt) {
		if (salt.length < MINIMUM_SALT_LENGTH) {
			log.warn("Provided salt less than "+MINIMUM_SALT_LENGTH+" bytes in size.");
			// throw new IllegalArgumentException(
			// "Provided salt must be at least 16 bytes in size.");
		}
		this.salt = salt;
	}

	public void setStoreLdap(boolean storeLdap) {
		this.storeLdap = storeLdap;
	}
	
	public void setDataSource(DataSource source) {
		if (source != null) {
			stStore = new SharedTokenStore(source);
		} else {
			log.error("DataSource should not be null");
			throw new IllegalArgumentException(
					"DataSource should not be null");
		}
	}

	/**
	 * @return the ldapConnectorId
	 */
	public String getLdapConnectorId() {
		return ldapConnectorId;
	}

	/**
	 * @param ldapConnectorId the ldapConnectorId to set
	 */
	public void setLdapConnectorId(String ldapConnectorId) {
		this.ldapConnectorId = ldapConnectorId;
	}


	/**
	 * @return the storedAttributeName
	 */
	public String getStoredAttributeName() {
		return storedAttributeName;
	}

	/**
	 * @param storedAttributeName the storedAttributeName to set
	 */
	public void setStoredAttributeName(String storedAttributeName) {
		this.storedAttributeName = storedAttributeName;
	}

}
