/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.crawler.connectors.docs4u;

// These are the basic interfaces we'll need from ManifoldCF
import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.crawler.interfaces.*;

// Utility includes
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

// This is where we get pull-agent system loggers
import org.apache.manifoldcf.crawler.system.Logging;

// This class implements system-wide static methods
import org.apache.manifoldcf.crawler.system.ManifoldCF;

// This is the base repository class.
import org.apache.manifoldcf.crawler.connectors.BaseRepositoryConnector;

// Here's the UI helper classes.
import org.apache.manifoldcf.ui.util.Encoder;

// Here are the imports that are specific for this connector
import org.apache.manifoldcf.examples.docs4u.Docs4UAPI;
import org.apache.manifoldcf.examples.docs4u.D4UFactory;
import org.apache.manifoldcf.examples.docs4u.D4UDocInfo;
import org.apache.manifoldcf.examples.docs4u.D4UDocumentIterator;
import org.apache.manifoldcf.examples.docs4u.D4UException;

/** This is the Docs4U repository connector class.  This extends the base connectors class,
* which implements IRepositoryConnector, and provides some degree of insulation from future
* changes to the IRepositoryConnector interface.  It also provides us with basic support for
* the connector lifecycle methods, so we don't have to implement those each time.
*/
public class Docs4UConnector extends BaseRepositoryConnector
{
  // These are the configuration parameter names
  
  /** Repository root parameter */
  protected final static String PARAMETER_REPOSITORY_ROOT = "rootdirectory";
  
  // These are the document specification node names
  
  /** Parameter to include in document search */
  protected final static String NODE_FIND_PARAMETER = "findparameter";
  /** Metadata to include with indexed documents */
  protected final static String NODE_INCLUDED_METADATA = "includedmetadata";
  
  // These are attribute names, which are shared among the nodes
  
  /** A name */
  protected final static String ATTRIBUTE_NAME = "name";
  /** A value */
  protected final static String ATTRIBUTE_VALUE = "value";
  
  // These are the activity names
  
  /** Fetch activity */
  protected final static String ACTIVITY_FETCH = "fetch";
  
  // Local constants
  
  /** Session expiration time interval */
  protected final static long SESSION_EXPIRATION_MILLISECONDS = 300000L;
  
  // The global deny token
  
  /** Global deny token for Docs4U */
  public final static String globalDenyToken = GLOBAL_DENY_TOKEN;
  
  // Local variables.
  
  /** The root directory */
  protected String rootDirectory = null;
  
  /** The Docs4U API session */
  protected Docs4UAPI session = null;
  /** The expiration time of the Docs4U API session */
  protected long sessionExpiration = -1L;
  
  /** Constructor */
  public Docs4UConnector()
  {
    super();
  }

  /** Tell the world what model this connector uses for addSeedDocuments().
  * This must return a model value as specified above.  The connector does not have to be connected
  * for this method to be called.
  *@return the model type value.
  */
  @Override
  public int getConnectorModel()
  {
    return MODEL_ADD_CHANGE;
  }

  /** Return the list of activities that this connector supports (i.e. writes into the log).
  * The connector does not have to be connected for this method to be called.
  *@return the list.
  */
  @Override
  public String[] getActivitiesList()
  {
    return new String[]{ACTIVITY_FETCH};
  }

  /** Return the list of relationship types that this connector recognizes.
  * The connector does not need to be connected for this method to be called.
  *@return the list.
  */
  @Override
  public String[] getRelationshipTypes()
  {
    return new String[0];
  }

  /** Output the configuration header section.
  * This method is called in the head section of the connector's configuration page.  Its purpose is to
  * add the required tabs to the list, and to output any javascript methods that might be needed by
  * the configuration editing HTML.
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputConfigurationHeader(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    tabsArray.add("Repository");
    Messages.outputResourceWithVelocity(out,locale,"ConfigurationHeader.html",null);
  }

  /** Output the configuration body section.
  * This method is called in the body section of the connector's configuration page.  Its purpose is to
  * present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within
  * appropriate <html>, <body>, and <form> tags.  The name of the form is always "editconnection".
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputConfigurationBody(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters, String tabName)
    throws ManifoldCFException, IOException
  {
    // Output the Repository tab
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInRepositoryTab(velocityContext,parameters);
    Messages.outputResourceWithVelocity(out,locale,"Configuration_Repository.html",velocityContext);
  }
  
  /** Fill in Velocity context for Repository tab.
  */
  protected static void fillInRepositoryTab(Map<String,Object> velocityContext,
    ConfigParams parameters)
  {
    String repositoryRoot = parameters.getParameter(PARAMETER_REPOSITORY_ROOT);
    if (repositoryRoot == null)
      repositoryRoot = "";
    velocityContext.put("repositoryroot",repositoryRoot);
  }
  
  /** Process a configuration post.
  * This method is called at the start of the connector's configuration page, whenever there is a possibility
  * that form data for a connection has been posted.  Its purpose is to gather form information and modify
  * the configuration parameters accordingly.
  * The name of the posted form is always "editconnection".
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param variableContext is the set of variables available from the post, including binary file post information.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of the
  *   connection (and cause a redirection to an error page).
  */
  @Override
  public String processConfigurationPost(IThreadContext threadContext, IPostParameters variableContext,
    ConfigParams parameters)
    throws ManifoldCFException
  {
    String repositoryRoot = variableContext.getParameter("repositoryroot");
    if (repositoryRoot != null)
      parameters.setParameter(PARAMETER_REPOSITORY_ROOT,repositoryRoot);
    return null;
  }

  /** View configuration.
  * This method is called in the body section of the connector's view configuration page.  Its purpose is to present
  * the connection information to the user.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate <html> and <body> tags.
  * The connector does not need to be connected for this method to be called.
  *@param threadContext is the local thread context.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param parameters are the configuration parameters, as they currently exist, for this connection being configured.
  */
  @Override
  public void viewConfiguration(IThreadContext threadContext, IHTTPOutput out,
    Locale locale, ConfigParams parameters)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    fillInRepositoryTab(velocityContext,parameters);
    Messages.outputResourceWithVelocity(out,locale,"ConfigurationView.html",velocityContext);
  }
  
  /** Get the current session, or create one if not valid.
  */
  protected Docs4UAPI getSession()
    throws ManifoldCFException, ServiceInterruption
  {
    if (session == null)
    {
      // We need to establish a new session
      try
      {
        session = D4UFactory.makeAPI(rootDirectory);
      }
      catch (D4UException e)
      {
        // Here we need to decide if the exception is transient or permanent.
        // Permanent exceptions should throw ManifoldCFException.  Transient
        // ones should throw an appropriate ServiceInterruption, based on the
        // actual error.
        Logging.connectors.warn("Docs4U: Session setup error: "+e.getMessage(),e);
        throw new ManifoldCFException("Session setup error: "+e.getMessage(),e);
      }
    }
    // Reset the expiration time
    sessionExpiration = System.currentTimeMillis() + SESSION_EXPIRATION_MILLISECONDS;
    return session;
  }
  
  /** Expire any current session.
  */
  protected void expireSession()
  {
    session = null;
    sessionExpiration = -1L;
  }
  
  /** Connect.
  *@param configParameters is the set of configuration parameters, which
  * in this case describe the root directory.
  */
  @Override
  public void connect(ConfigParams configParameters)
  {
    super.connect(configParameters);
    // This is needed by getDocumentBins()
    rootDirectory = configParameters.getParameter(PARAMETER_REPOSITORY_ROOT);
  }

  /** Close the connection.  Call this before discarding this instance of the
  * repository connector.
  */
  @Override
  public void disconnect()
    throws ManifoldCFException
  {
    expireSession();
    rootDirectory = null;
    super.disconnect();
  }

  /** Test the connection.  Returns a string describing the connection integrity.
  *@return the connection's status as a displayable string.
  */
  @Override
  public String check()
    throws ManifoldCFException
  {
    try
    {
      // Get or establish the session
      Docs4UAPI currentSession = getSession();
      // Check session integrity
      try
      {
        currentSession.sanityCheck();
      }
      catch (D4UException e)
      {
        Logging.connectors.warn("Docs4U: Error checking repository: "+e.getMessage(),e);
        return "Error: "+e.getMessage();
      }
      // If it passed, return "everything ok" message
      return super.check();
    }
    catch (ServiceInterruption e)
    {
      // Convert service interruption into a transient error for display
      return "Transient error: "+e.getMessage();
    }
  }

  /** This method is periodically called for all connectors that are connected but not
  * in active use.
  */
  @Override
  public void poll()
    throws ManifoldCFException
  {
    if (session != null)
    {
      if (System.currentTimeMillis() >= sessionExpiration)
        expireSession();
    }
  }

  /** Get the bin name strings for a document identifier.  The bin name describes the queue to which the
  * document will be assigned for throttling purposes.  Throttling controls the rate at which items in a
  * given queue are fetched; it does not say anything about the overall fetch rate, which may operate on
  * multiple queues or bins.
  * For example, if you implement a web crawler, a good choice of bin name would be the server name, since
  * that is likely to correspond to a real resource that will need real throttle protection.
  * The connector must be connected for this method to be called.
  *@param documentIdentifier is the document identifier.
  *@return the set of bin names.  If an empty array is returned, it is equivalent to there being no request
  * rate throttling available for this identifier.
  */
  @Override
  public String[] getBinNames(String documentIdentifier)
  {
    return new String[]{rootDirectory};
  }
  
  /** Request arbitrary connector information.
  * This method is called directly from the API in order to allow API users to perform any one of several
  * connector-specific queries.  These are usually used to create external UI's.  The connector will be
  * connected before this method is called.
  *@param output is the response object, to be filled in by this method.
  *@param command is the command, which is taken directly from the API request.
  *@return true if the resource is found, false if not.  In either case, output may be filled in.
  */
  @Override
  public boolean requestInfo(Configuration output, String command)
    throws ManifoldCFException
  {
    // Look for the commands we know about
    if (command.equals("metadata"))
    {
      // Use a try/catch to capture errors from repository communication
      try
      {
        // Get the metadata names
        String[] metadataNames = getMetadataNames();
        // Code these up in the output, in a form that yields decent JSON
        for (String metadataName : metadataNames)
        {
          // Construct an appropriate node
          ConfigurationNode node = new ConfigurationNode("metadata");
          ConfigurationNode child = new ConfigurationNode("name");
          child.setValue(metadataName);
          node.addChild(node.getChildCount(),child);
          output.addChild(output.getChildCount(),node);
        }
      }
      catch (ServiceInterruption e)
      {
        ManifoldCF.createServiceInterruptionNode(output,e);
      }
      catch (ManifoldCFException e)
      {
        ManifoldCF.createErrorNode(output,e);
      }
    }
    else
      return super.requestInfo(output,command);
    return true;
  }

  /** Queue "seed" documents.  Seed documents are the starting places for crawling activity.  Documents
  * are seeded when this method calls appropriate methods in the passed in ISeedingActivity object.
  *
  * This method can choose to find repository changes that happen only during the specified time interval.
  * The seeds recorded by this method will be viewed by the framework based on what the
  * getConnectorModel() method returns.
  *
  * It is not a big problem if the connector chooses to create more seeds than are
  * strictly necessary; it is merely a question of overall work required.
  *
  * The times passed to this method may be interpreted for greatest efficiency.  The time ranges
  * any given job uses with this connector will not overlap, but will proceed starting at 0 and going
  * to the "current time", each time the job is run.  For continuous crawling jobs, this method will
  * be called once, when the job starts, and at various periodic intervals as the job executes.
  *
  * When a job's specification is changed, the framework automatically resets the seeding start time to 0.  The
  * seeding start time may also be set to 0 on each job run, depending on the connector model returned by
  * getConnectorModel().
  *
  * Note that it is always ok to send MORE documents rather than less to this method.
  * The connector will be connected before this method can be called.
  *@param activities is the interface this method should use to perform whatever framework actions are desired.
  *@param spec is a document specification (that comes from the job).
  *@param startTime is the beginning of the time range to consider, inclusive.
  *@param endTime is the end of the time range to consider, exclusive.
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  */
  @Override
  public void addSeedDocuments(ISeedingActivity activities, DocumentSpecification spec,
    long startTime, long endTime, int jobMode)
    throws ManifoldCFException, ServiceInterruption
  {
    // Get a session handle
    Docs4UAPI currentSession = getSession();
    // Scan document specification for findparameter nodes
    int i = 0;
    while (i < spec.getChildCount())
    {
      SpecificationNode sn = spec.getChild(i++);
      if (sn.getType().equals(NODE_FIND_PARAMETER))
      {
        // Found a findparameter node.  Execute a Docs4U query based on it.
        String findParameterName = sn.getAttributeValue(ATTRIBUTE_NAME);
        String findParameterValue = sn.getAttributeValue(ATTRIBUTE_VALUE);
        Map findMap = new HashMap();
        findMap.put(findParameterName,findParameterValue);
        try
        {
          if (Logging.connectors.isDebugEnabled())
            Logging.connectors.debug("Docs4U: Finding documents where "+findParameterName+
              "= '"+findParameterValue+"'");
          D4UDocumentIterator iter = currentSession.findDocuments(new Long(startTime),
            new Long(endTime),findMap);
          while (iter.hasNext())
          {
            String docID = iter.getNext();
            // Add this to the job queue
            activities.addSeedDocument(docID);
          }
        }
        catch (InterruptedException e)
        {
          throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
        }
        catch (D4UException e)
        {
          Logging.connectors.warn("Docs4U: Error finding documents: "+e.getMessage(),e);
          throw new ManifoldCFException(e.getMessage(),e);
        }
      }
    }
  }

  /** Get document versions given an array of document identifiers.
  * This method is called for EVERY document that is considered. It is therefore important to perform
  * as little work as possible here.
  * The connector will be connected before this method can be called.
  *@param documentIdentifiers is the array of local document identifiers, as understood by this connector.
  *@param oldVersions is the corresponding array of version strings that have been saved for the document identifiers.
  *   A null value indicates that this is a first-time fetch, while an empty string indicates that the previous document
  *   had an empty version string.
  *@param activities is the interface this method should use to perform whatever framework actions are desired.
  *@param spec is the current document specification for the current job.  If there is a dependency on this
  * specification, then the version string should include the pertinent data, so that reingestion will occur
  * when the specification changes.  This is primarily useful for metadata.
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  *@param usesDefaultAuthority will be true only if the authority in use for these documents is the default one.
  *@return the corresponding version strings, with null in the places where the document no longer exists.
  * Empty version strings indicate that there is no versioning ability for the corresponding document, and the document
  * will always be processed.
  */
  @Override
  public String[] getDocumentVersions(String[] documentIdentifiers, String[] oldVersions, IVersionActivity activities,
    DocumentSpecification spec, int jobMode, boolean usesDefaultAuthority)
    throws ManifoldCFException, ServiceInterruption
  {
    // First, the metadata specified will affect the indexing of the document.
    // So, we need to put the metadata specification as it currently exists into the version string.
    List<String> metadataNames = new ArrayList<String>();
    int i = 0;
    while (i < spec.getChildCount())
    {
      SpecificationNode sn = spec.getChild(i++);
      if (sn.getType().equals(NODE_INCLUDED_METADATA))
        metadataNames.add(sn.getAttributeValue(ATTRIBUTE_NAME));
    }
    // Sort the list of metadata names, since it will be used for a version string
    String[] namesToVersion = metadataNames.toArray(new String[0]);
    java.util.Arrays.sort(namesToVersion);
    
    // Get the current docs4u session
    Docs4UAPI currentSession = getSession();
    // Prepare a place for the return values
    String[] rval = new String[documentIdentifiers.length];
    // Capture Docs4U exceptions
    try
    {
      i = 0;
      while (i < documentIdentifiers.length)
      {
        if (Logging.connectors.isDebugEnabled())
          Logging.connectors.debug("Docs4U: Getting update time for '"+documentIdentifiers[i]+"'");
        Long time = currentSession.getDocumentUpdatedTime(documentIdentifiers[i]);
        // A null return means the document doesn't exist
        if (time == null)
          rval[i] = null;
        else
        {
          StringBuilder versionBuffer = new StringBuilder();
          // Pack the metadata names.
          packList(versionBuffer,namesToVersion,'+');
          // Add the updated time.
          versionBuffer.append(time.toString());
          rval[i] = versionBuffer.toString();
        }
        i++;
      }
      return rval;
    }
    catch (InterruptedException e)
    {
      throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (D4UException e)
    {
      Logging.connectors.warn("Docs4U: Error versioning documents: "+e.getMessage(),e);
      throw new ManifoldCFException(e.getMessage(),e);
    }
  }

  /** Process a set of documents.
  * This is the method that should cause each document to be fetched, processed, and the results either added
  * to the queue of documents for the current job, and/or entered into the incremental ingestion manager.
  * The document specification allows this class to filter what is done based on the job.
  * The connector will be connected before this method can be called.
  *@param documentIdentifiers is the set of document identifiers to process.
  *@param versions is the corresponding document versions to process, as returned by getDocumentVersions() above.
  *       The implementation may choose to ignore this parameter and always process the current version.
  *@param activities is the interface this method should use to queue up new document references
  * and ingest documents.
  *@param spec is the document specification.
  *@param scanOnly is an array corresponding to the document identifiers.  It is set to true to indicate when the processing
  * should only find other references, and should not actually call the ingestion methods.
  *@param jobMode is an integer describing how the job is being run, whether continuous or once-only.
  */
  @Override
  public void processDocuments(String[] documentIdentifiers, String[] versions, IProcessActivity activities,
    DocumentSpecification spec, boolean[] scanOnly, int jobMode)
    throws ManifoldCFException, ServiceInterruption
  {
    // Get the current session
    Docs4UAPI currentSession = getSession();
    // Capture exceptions from Docs4U
    try
    {
      for (int i = 0; i < documentIdentifiers.length; i++)
      {
        // ScanOnly indicates that we should only extract, never index.
        if (!scanOnly[i])
        {
          String docID = documentIdentifiers[i];
          String version = versions[i];
          
          // Fetch and index the document.  First, we fetch, but we keep track of time.
          
          // Set up variables for recording the fetch activity status
          long startTime = System.currentTimeMillis();
          long dataSize = 0L;
          String status = "OK";
          String description = null;
          boolean fetchOccurred = false;
          
          D4UDocInfo docData = D4UFactory.makeDocInfo();
          try
          {
            // Get the document's URL first, so we don't have a potential race condition.
            String url = currentSession.getDocumentURL(docID);
            if (url == null || currentSession.getDocument(docID,docData) == false)
            {
              // Not found: delete it
              activities.deleteDocument(docID);
            }
            else
            {
              // Found: index it
              fetchOccurred = true;
              RepositoryDocument rd = new RepositoryDocument();
              InputStream is = docData.readData();
              if (is != null)
              {
                try
                {
                  // Set the contents
                  dataSize = docData.readDataLength().longValue();
                  rd.setBinary(is,dataSize);
                  
                  // Unpack metadata info
                  List<String> metadataNames = new ArrayList<String>();
                  unpackList(metadataNames,version,0,'+');
                  for (String metadataName : metadataNames)
                  {
                    // Get the value from the doc info object
                    String[] metadataValues = docData.getMetadata(metadataName);
                    if (metadataValues != null)
                    {
                      // Add to the repository document
                      rd.addField(metadataName,metadataValues);
                    }
                  }
                  
                  // Handle the security information
                  rd.setSecurityACL(RepositoryDocument.SECURITY_TYPE_DOCUMENT,
                    docData.getAllowed());
                  // For disallowed, we must add in a global deny token
                  String[] disallowed = docData.getDisallowed();
                  List<String> list = new ArrayList<String>();
                  list.add(globalDenyToken);
                  for (String disallowedToken : disallowed)
                  {
                    list.add(disallowedToken);
                  }
                  rd.setSecurityDenyACL(RepositoryDocument.SECURITY_TYPE_DOCUMENT,
                    list.toArray(disallowed));
                  
                  // Index the document!
                  activities.ingestDocument(docID,version,url,rd);
                }
                finally
                {
                  is.close();
                }
              }
            }
          }
          catch (D4UException e)
          {
            status = "ERROR";
            description = e.getMessage();
            throw e;
          }
          finally
          {
            docData.close();
            // Record the status
            if (fetchOccurred)
              activities.recordActivity(new Long(startTime),ACTIVITY_FETCH,dataSize,docID,status,description,null);
          }
        }
      }
    }
    catch (InterruptedIOException e)
    {
      throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (IOException e)
    {
      Logging.connectors.warn("Docs4U: Error transferring files: "+e.getMessage(),e);
      throw new ManifoldCFException(e.getMessage(),e);
    }
    catch (InterruptedException e)
    {
      throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (D4UException e)
    {
      Logging.connectors.warn("Docs4U: Error getting documents: "+e.getMessage(),e);
      throw new ManifoldCFException(e.getMessage(),e);
    }
  }

  /** Free a set of documents.  This method is called for all documents whose versions have been fetched using
  * the getDocumentVersions() method, including those that returned null versions.  It may be used to free resources
  * committed during the getDocumentVersions() method.  It is guaranteed to be called AFTER any calls to
  * processDocuments() for the documents in question.
  * The connector will be connected before this method can be called.
  *@param documentIdentifiers is the set of document identifiers.
  *@param versions is the corresponding set of version identifiers (individual identifiers may be null).
  */
  @Override
  public void releaseDocumentVersions(String[] documentIdentifiers, String[] versions)
    throws ManifoldCFException
  {
    // Nothing needed
  }

  /** Get the maximum number of documents to amalgamate together into one batch, for this connector.
  * The connector does not need to be connected for this method to be called.
  *@return the maximum number. 0 indicates "unlimited".
  */
  @Override
  public int getMaxDocumentRequest()
  {
    return 1;
  }

  // UI support methods.
  //
  // The UI support methods come in two varieties.  The first group (inherited from IConnector) is involved
  //  in setting up connection configuration information.
  //
  // The second group is listed here.  These methods are is involved in presenting and editing document specification
  //  information for a job.
  //
  // The two kinds of methods are accordingly treated differently, in that the first group cannot assume that
  // the current connector object is connected, while the second group can.  That is why the first group
  // receives a thread context argument for all UI methods, while the second group does not need one
  // (since it has already been applied via the connect() method).
    
  /** Output the specification header section.
  * This method is called in the head section of a job page which has selected a repository connection of the
  * current type.  Its purpose is to add the required tabs to the list, and to output any javascript methods
  * that might be needed by the job editing HTML.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param ds is the current document specification for this job.
  *@param tabsArray is an array of tab names.  Add to this array any tab names that are specific to the connector.
  */
  @Override
  public void outputSpecificationHeader(IHTTPOutput out, Locale locale,
    DocumentSpecification ds, List<String> tabsArray)
    throws ManifoldCFException, IOException
  {
    // Add the tabs
    tabsArray.add("Documents");
    tabsArray.add("Metadata");
    Messages.outputResourceWithVelocity(out,locale,"SpecificationHeader.html",null);
  }
    
  /** Output the specification body section.
  * This method is called in the body section of a job page which has selected a repository connection of the
  * current type.  Its purpose is to present the required form elements for editing.
  * The coder can presume that the HTML that is output from this configuration will be within appropriate
  *  <html>, <body>, and <form> tags.  The name of the form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param ds is the current document specification for this job.
  *@param tabName is the current tab name.
  */
  @Override
  public void outputSpecificationBody(IHTTPOutput out, Locale locale,
    DocumentSpecification ds, String tabName)
    throws ManifoldCFException, IOException
  {
    // Do the "Documents" tab
    outputDocumentsTab(out,locale,ds,tabName);
    // Do the "Metadata" tab
    outputMetadataTab(out,locale,ds,tabName);
  }
  
  /** Take care of "Documents" tab.
  */
  protected void outputDocumentsTab(IHTTPOutput out, Locale locale,
    DocumentSpecification ds, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInDocumentsTab(velocityContext,ds);
    fillInMetadataSelection(velocityContext);
    Messages.outputResourceWithVelocity(out,locale,"Specification_Documents.html",velocityContext);
  }
  
  /** Fill in Velocity context with data for Documents tab.
  */
  protected static void fillInDocumentsTab(Map<String,Object> velocityContext,
    DocumentSpecification ds)
  {
    List<Map<String,String>> list = new ArrayList<Map<String,String>>();
    int i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      // Look for FIND_PARAMETER nodes in the document specification
      if (sn.getType().equals(NODE_FIND_PARAMETER))
      {
        // Pull the metadata name and value from the FIND_PARAMETER node
        String findParameterName = sn.getAttributeValue(ATTRIBUTE_NAME);
        String findParameterValue = sn.getAttributeValue(ATTRIBUTE_VALUE);
        Map<String,String> row = new HashMap<String,String>();
        row.put("name",findParameterName);
        row.put("value",findParameterValue);
        list.add(row);
      }
    }
    velocityContext.put("matches",list);
  }
  
  /** Fill in Velocity context with data to permit attribute selection.
  */
  protected void fillInMetadataSelection(Map<String,Object> velocityContext)
  {
    try
    {
      String[] matchNames = getMetadataNames();
      velocityContext.put("metadataattributes",matchNames);
      velocityContext.put("error","");
    }
    catch (ManifoldCFException e)
    {
      velocityContext.put("error","Error: "+e.getMessage());
    }
    catch (ServiceInterruption e)
    {
      velocityContext.put("error","Transient error: "+e.getMessage());
    }
  }
  
  /** Take care of "Metadata" tab.
  */
  protected void outputMetadataTab(IHTTPOutput out, Locale locale,
    DocumentSpecification ds, String tabName)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    velocityContext.put("TabName",tabName);
    fillInMetadataTab(velocityContext,ds);
    fillInMetadataSelection(velocityContext);
    Messages.outputResourceWithVelocity(out,locale,"Specification_Metadata.html",velocityContext);
  }
  
  /** Fill in Velocity context for Metadata tab.
  */
  protected static void fillInMetadataTab(Map<String,Object> velocityContext,
    DocumentSpecification ds)
  {
    Set<String> metadataSelections = new HashSet<String>();
    int i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i++);
      if (sn.getType().equals(NODE_INCLUDED_METADATA))
      {
        String metadataName = sn.getAttributeValue(ATTRIBUTE_NAME);
        metadataSelections.add(metadataName);
      }
    }
    velocityContext.put("metadataselections",metadataSelections);
  }
  
  /** Process a specification post.
  * This method is called at the start of job's edit or view page, whenever there is a possibility that form
  * data for a connection has been posted.  Its purpose is to gather form information and modify the
  * document specification accordingly.  The name of the posted form is always "editjob".
  * The connector will be connected before this method can be called.
  *@param variableContext contains the post data, including binary file-upload information.
  *@param ds is the current document specification for this job.
  *@return null if all is well, or a string error message if there is an error that should prevent saving of
  * the job (and cause a redirection to an error page).
  */
  @Override
  public String processSpecificationPost(IPostParameters variableContext, DocumentSpecification ds)
    throws ManifoldCFException
  {
    // Pick up the Documents tab data
    String rval = processDocumentsTab(variableContext,ds);
    if (rval != null)
      return rval;
    // Pick up the Metadata tab data
    rval = processMetadataTab(variableContext,ds);
    return rval;
  }
  
  /** Process form post for Documents tab.
  */
  protected String processDocumentsTab(IPostParameters variableContext, DocumentSpecification ds)
    throws ManifoldCFException
  {
    // Remove old find parameter document specification information
    removeNodes(ds,NODE_FIND_PARAMETER);
    
    // Parse the number of records that were posted
    String findCountString = variableContext.getParameter("findcount");
    if (findCountString != null)
    {
      int findCount = Integer.parseInt(findCountString);
              
      // Loop throught them and add to the new document specification information
      int i = 0;
      while (i < findCount)
      {
        String suffix = "_"+Integer.toString(i++);
        // Only add the name/value if the item was not deleted.
        String findParameterOp = variableContext.getParameter("findop"+suffix);
        if (findParameterOp == null || !findParameterOp.equals("Delete"))
        {
          String findParameterName = variableContext.getParameter("findname"+suffix);
          String findParameterValue = variableContext.getParameter("findvalue"+suffix);
          addFindParameterNode(ds,findParameterName,findParameterValue);
        }
      }
    }
      
    // Now, look for a global "Add" operation
    String operation = variableContext.getParameter("findop");
    if (operation != null && operation.equals("Add"))
    {
      // Pick up the global parameter name and value
      String findParameterName = variableContext.getParameter("findname");
      String findParameterValue = variableContext.getParameter("findvalue");
      addFindParameterNode(ds,findParameterName,findParameterValue);
    }

    return null;
  }


  /** Process form post for Metadata tab.
  */
  protected String processMetadataTab(IPostParameters variableContext, DocumentSpecification ds)
    throws ManifoldCFException
  {
    // Remove old included metadata nodes
    removeNodes(ds,NODE_INCLUDED_METADATA);

    // Get the posted metadata values
    String[] metadataNames = variableContext.getParameterValues("metadata");
    if (metadataNames != null)
    {
      // Add each metadata name as a node to the document specification
      int i = 0;
      while (i < metadataNames.length)
      {
        String metadataName = metadataNames[i++];
        addIncludedMetadataNode(ds,metadataName);
      }
    }
    
    return null;
  }

  /** Add a FIND_PARAMETER node to a document specification.
  */
  protected static void addFindParameterNode(DocumentSpecification ds,
    String findParameterName, String findParameterValue)
  {
    // Create a new specification node with the right characteristics
    SpecificationNode sn = new SpecificationNode(NODE_FIND_PARAMETER);
    sn.setAttribute(ATTRIBUTE_NAME,findParameterName);
    sn.setAttribute(ATTRIBUTE_VALUE,findParameterValue);
    // Add to the end
    ds.addChild(ds.getChildCount(),sn);
  }

  /** Add an INCLUDED_METADATA node to a document specification.
  */
  protected static void addIncludedMetadataNode(DocumentSpecification ds,
    String metadataName)
  {
    // Build the proper node
    SpecificationNode sn = new SpecificationNode(NODE_INCLUDED_METADATA);
    sn.setAttribute(ATTRIBUTE_NAME,metadataName);
    // Add to the end
    ds.addChild(ds.getChildCount(),sn);
  }
  
  /** Remove all of a specified node type from a document specification.
  */
  protected static void removeNodes(DocumentSpecification ds,
    String nodeTypeName)
  {
    int i = 0;
    while (i < ds.getChildCount())
    {
      SpecificationNode sn = ds.getChild(i);
      if (sn.getType().equals(nodeTypeName))
        ds.removeChild(i);
      else
        i++;
    }
  }
  
  /** View specification.
  * This method is called in the body section of a job's view page.  Its purpose is to present the document
  * specification information to the user.  The coder can presume that the HTML that is output from
  * this configuration will be within appropriate <html> and <body> tags.
  * The connector will be connected before this method can be called.
  *@param out is the output to which any HTML should be sent.
  *@param locale is the desired locale.
  *@param ds is the current document specification for this job.
  */
  @Override
  public void viewSpecification(IHTTPOutput out, Locale locale, DocumentSpecification ds)
    throws ManifoldCFException, IOException
  {
    Map<String,Object> velocityContext = new HashMap<String,Object>();
    fillInDocumentsTab(velocityContext,ds);
    fillInMetadataTab(velocityContext,ds);
    Messages.outputResourceWithVelocity(out,locale,"SpecificationView.html",velocityContext);
  }

  // Protected UI support methods
  
  /** Get an ordered list of metadata names.
  */
  protected String[] getMetadataNames()
    throws ManifoldCFException, ServiceInterruption
  {
    Docs4UAPI currentSession = getSession();
    try
    {
      String[] rval = currentSession.getMetadataNames();
      java.util.Arrays.sort(rval);
      return rval;
    }
    catch (InterruptedException e)
    {
      throw new ManifoldCFException(e.getMessage(),e,ManifoldCFException.INTERRUPTED);
    }
    catch (D4UException e)
    {
      throw new ManifoldCFException(e.getMessage(),e);
    }
  }
  
}

