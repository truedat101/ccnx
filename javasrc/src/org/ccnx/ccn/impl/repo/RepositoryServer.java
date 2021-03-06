/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNStats;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats;
import org.ccnx.ccn.impl.CCNStats.CCNStatistics;
import org.ccnx.ccn.impl.CCNStats.CCNEnumStats.IStatsEnum;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNWriter;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.PublicKeyObject;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.ccnd.PrefixRegistrationManager;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage.NameEnumerationResponseMessageObject;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.SignedInfo;
import org.ccnx.ccn.protocol.KeyLocator.KeyLocatorType;

/**
 * High level implementation of repository protocol that
 * can be used by any application to provide repository service. 
 * The application must supply a RepositoryStore instance to take care of actual storage
 * and retrieval, which might use persistent storage or application data structures.
 * 
 * Some notes:
 * We use a policy file to decide which namespaces to save. The policy file
 * is currently parsed within the lower level.
 * 
 * We can't just express an interest in anything that's within the namespaces
 * that we want to save within, because we will keep getting back the same
 * content over and over again if we do that. Instead the clients trigger a
 * write to the repository by expressing an interest in contentName +
 * "write_marker" (CCNBase.REPO_START_WRITE). When we see this we write back
 * some information then express an interest in the contentName without the
 * "write_marker" to get the initial block, then express interest in blocks
 * after the initial block for this particular write.
 */

public class RepositoryServer implements CCNStatistics {
	private final int REPO_PREFIX_FLAGS = PrefixRegistrationManager.DEFAULT_SELF_REG_FLAGS 
				+ PrefixRegistrationManager.CCN_FORW_TAP;
	private RepositoryStore _repo = null;
	private CCNHandle _handle = null;
	private ArrayList<ContentName> _repoFilters = new ArrayList<ContentName>();
	private ArrayList<RepositoryDataListener> _currentListeners = new ArrayList<RepositoryDataListener>();
	private Exclude _markerFilter;
	private CCNWriter _writer;
	private boolean _pendingNameSpaceChange = false;
	private int _windowSize = SystemConfiguration.PIPELINE_SIZE;
	private int _ephemeralFreshness = FRESHNESS;
	private RepositoryDataHandler _dataHandler;
	private ContentName _responseName = null;
	
	public static final int PERIOD = 2000; // period for interest timeout check in ms.
	public static final int THREAD_LIFE = 8;	// in seconds
	//public static final int WINDOW_SIZE = 4;
	public static final int FRESHNESS = 4;	// in seconds
		
	protected Timer _periodicTimer = null;
	protected RepositoryInterestHandler _iHandler = null;
	
	private class InterestTimer extends TimerTask {

		public void run() {
			long currentTime = System.currentTimeMillis();
			synchronized (_currentListeners) {
				if (_currentListeners.size() > 0) {
					Iterator<RepositoryDataListener> iterator = _currentListeners.iterator();
					while (iterator.hasNext()) {
						RepositoryDataListener listener = iterator.next();
						if ((currentTime - listener.getTimer()) > SystemConfiguration.MAX_TIMEOUT) {
							synchronized(_repoFilters) {
								listener.cancelInterests();
								iterator.remove();
							}
						}
					}
				}
			
				if (_currentListeners.size() == 0 && _pendingNameSpaceChange) {
					if( Log.isLoggable(Log.FAC_REPO, Level.FINER) )
						Log.finer(Log.FAC_REPO, "InterestTimer - resetting nameSpace");
					try {
						resetNameSpace();
					} catch (IOException e) {
						Log.logStackTrace(Level.WARNING, e);
						e.printStackTrace();
					}
					_pendingNameSpaceChange = false;
					_currentListeners.notify();
				}
			}
		}	
	}

	/**
	 * Constructor.  Note that merely creating an instance does not begin service 
	 * of requests from the network.  For that you must call start().
	 * @param repo the RepositoryStore instance to use for backing storage.
	 * 	The RepositoryServer uses repo.getHandle() to get the handle to use
	 * 	for communication, to make sure that it is sending messages under the
	 * 	identity of the repo.
	 * @throws IOException
	 */
	public RepositoryServer(RepositoryStore repo) throws IOException {
			_repo = repo;
			_handle = repo.getHandle();
			_writer = new CCNWriter(_handle);
			_iHandler = new RepositoryInterestHandler(this);
			
			_responseName = KeyProfile.keyName(null, _handle.keyManager().getDefaultKeyID());

			 // At some point we may want to refactor the code to
			 // write repository info back in a stream.  But for now
			 // we're just doing a simple put and the writer could be
			 // writing anywhere so the simplest thing to do is to just
			 // disable flow control
			_writer.disableFlowControl();

			_dataHandler = new RepositoryDataHandler(this);
			Thread dataHandlerThread = new Thread(_dataHandler, "RepositoryDataHandler");
			dataHandlerThread.start();
	}

	/**
	 * Start serving requests from the network
	 */
	public void start() {
		Log.info(Log.FAC_REPO, "Starting service of repository requests");
		try {
			resetNameSpace();
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		
		byte[][]markerOmissions = new byte[2][];
		markerOmissions[0] = CommandMarker.COMMAND_MARKER_REPO_START_WRITE.getBytes();
		markerOmissions[1] = CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes();
		_markerFilter = new Exclude(markerOmissions);
		
		_periodicTimer = new Timer(true);
		_periodicTimer.scheduleAtFixedRate(new InterestTimer(), PERIOD, PERIOD);

	}
	
	/**
	 * Stop serving requests
	 */
	public void shutDown() {
		Log.info(Log.FAC_REPO, "Stopping service of repository requests");

		// This closes our handle....
		_handle = null;
		_repo.shutDown();
		
		if( _periodicTimer != null ) {
			synchronized (_currentListeners) {
				if (_currentListeners.size() != 0) {
					_pendingNameSpaceChange = true; // Don't allow any more requests to come in
					boolean interrupted;
					do {
						try {
							interrupted = false;
							_currentListeners.wait();
						} catch (InterruptedException e) {
							interrupted = true;
						}
					} while (interrupted);
				}
				_periodicTimer.cancel();
			}
		}
		
		_dataHandler.shutdown();
		
		try {
			_writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		
	}
	
	/**
	 * In general we need to wait until all sessions are complete before
	 * making a namespace change because it involves changing the filter which
	 * could cut off current sessions in process
	 * @throws IOException 
	 */
	public void resetNameSpaceFromHandler() throws IOException {
		synchronized (_currentListeners) {
			if (_currentListeners.size() == 0)
				resetNameSpace();
			else
				_pendingNameSpaceChange = true;
			if( Log.isLoggable(Log.FAC_REPO, Level.FINER) )
				Log.finer(Log.FAC_REPO, "ResetNameSpaceFromHandler: pendingNameSpaceChange is {0}", _pendingNameSpaceChange);
		}	
	}
	
	private void resetNameSpace() throws IOException {
		synchronized (_repoFilters) {
			ArrayList<ContentName> newIL = new ArrayList<ContentName>();
			synchronized (_repo.getPolicy()) {
				ArrayList<ContentName> newNameSpace = _repo.getNamespace();
				if (newNameSpace == null)
					newNameSpace = new ArrayList<ContentName>();
				ArrayList<ContentName> unMatchedOld = new ArrayList<ContentName>();
				ArrayList<ContentName> unMatchedNew = new ArrayList<ContentName>();
				getUnMatched(_repoFilters, newNameSpace, unMatchedOld, unMatchedNew);
				for (ContentName oldName : unMatchedOld) {
					_handle.unregisterFilter(oldName, _iHandler);
					if( Log.isLoggable(Log.FAC_REPO, Level.INFO) )
						Log.info(Log.FAC_REPO, "Dropping namespace {0}", oldName);
				}
				for (ContentName newName : unMatchedNew) {
					_handle.getNetworkManager().setInterestFilter(_handle, newName, _iHandler, REPO_PREFIX_FLAGS);
					if( Log.isLoggable(Log.FAC_REPO, Level.INFO) )
						Log.info(Log.FAC_REPO, "Adding namespace {0}", newName);
					newIL.add((newName));
				}
				_repoFilters = newIL;
			}
		}
	}
	
	/**
	 * Determine changes in the namespace so we can decide what needs to be newly registered
	 * and what should be unregistered. This also checks to see that no names duplicate each other
	 * i.e. if we have /foo and /foo/bar only /foo should be registered for the repo to listen
	 * to.
	 * 
	 * @param oldIn - previously registered names
	 * @param newIn - new names to consider
	 * @param oldOut - previously registered names to deregister
	 * @param newOut - new names which need to be registered
	 */
	@SuppressWarnings("unchecked")
	private void getUnMatched(ArrayList<ContentName> oldIn, ArrayList<ContentName> newIn, 
			ArrayList<ContentName> oldOut, ArrayList<ContentName>newOut) {
		newOut.addAll(newIn);
		for (ContentName ial : oldIn) {
			boolean matched = false;
			for (ContentName name : newIn) {
				if (ial.equals(name)) {
					newOut.remove(name);
					matched = true;
					break;
				}
			}
			if (!matched)
				oldOut.add(ial);
		}
		
		// Make sure no name can be expressed a prefix of another name in the list
		ArrayList<ContentName> tmpOut = (ArrayList<ContentName>)newOut.clone();
		for (ContentName cn : tmpOut) {
			for (ContentName tcn : tmpOut) {
				if (tcn.equals(cn))
					continue;
				if (tcn.isPrefixOf(cn))
					newOut.remove(cn);
				if (cn.isPrefixOf(tcn))
					newOut.remove(tcn);
			}
		}
	}
	
	public CCNHandle getHandle() {
		return _handle;
	}
	
	public RepositoryStore getRepository() {
		return _repo;
	}
	
	public Exclude getExcludes() {
		return _markerFilter;
	}
	
	public CCNWriter getWriter() {
		return _writer;
	}
	
	public ArrayList<RepositoryDataListener> getDataListeners() {
		return _currentListeners;
	}
	
	public ArrayList<ContentName> getFilters() {
		return _repoFilters;
	}
	
	public void addListener(RepositoryDataListener listener) {
		synchronized(_currentListeners) {
			_currentListeners.add(listener);
		}
	}
	
	public boolean getPendingNameSpaceState() {
		return _pendingNameSpaceChange;
	}
	
	public RepositoryDataHandler getDataHandler() {
		return _dataHandler;
	}
	
	public int getWindowSize() {
		return _windowSize;
	}
	
	public int getFreshness() {
		return _ephemeralFreshness;
	}
	
	public ContentName getResponseName() {
		return _responseName;
	}
	
	
	/**
	 * Method to write out name enumeration responses.  This is called directly
	 * to respond to incoming name enumeration interests and can also be called
	 * when a content object is saved in the repo and the interest flag is set
	 * by a previous name enumeration interest where there was not new information
	 * available.
	 * 
	 * @param ner NameEnumerationResponse object to send out
	 * 
	 * @return void
	 */
	public void sendEnumerationResponse(NameEnumerationResponse ner){
		if(ner!=null && ner.getPrefix()!=null && ner.hasNames()){
			NameEnumerationResponseMessageObject neResponseObject = null;
			try{
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
					Log.finer(Log.FAC_REPO, "returning names for prefix: {0}", ner.getPrefix());

				if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
					for (int x = 0; x < ner.getNames().size(); x++) {
						Log.finer("name: {0}", ner.getNames().get(x));
					}
				}
				if (ner.getTimestamp()==null)
					if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
						Log.info(Log.FAC_REPO, "node.timestamp was null!!!");
				NameEnumerationResponseMessage nem = ner.getNamesForResponse();
				neResponseObject = new NameEnumerationResponseMessageObject(new ContentName(ner.getPrefix(), _responseName.components()), nem, _handle);
				// TODO this is only temporary until flow control issues can
				// be worked out here
				neResponseObject.disableFlowControl();
				neResponseObject.save(ner.getTimestamp());
				if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
					Log.finer(Log.FAC_REPO, "saved collection object: {0}", neResponseObject.getVersionedName());
				return;

			} catch(IOException e){
				if (null != neResponseObject)
					Log.logException("error saving name enumeration response for write out (prefix = "+ner.getPrefix()+" collection name: "+neResponseObject.getVersionedName()+")", e);
				else
					Log.logException("error creating name enumeration response object for write out (prefix = "+ner.getPrefix()+")", e);
			}
		}
	}
	
	/**
	 * Look for unverified keys. Note that we must have already checked to see that the repo has
	 * the content for this target before calling this.
	 * 
	 * @param target
	 * @return new target if we need to verify the target
	 */
	public ContentName getKeyTarget(ContentName target) {
		if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
			Log.finer(Log.FAC_REPO, "Checking for key locators for: {0}", target);
		try {
			ContentObject content = _repo.getContent(new Interest(target));
			return getKeyTargetFromObject(content, target);
		} catch (RepositoryException e) {
			return null;
		}
	}
	
	/**
	 * Look for unverified keys based on content object
	 * 
	 * @param content
	 * @param target
	 * @return
	 * @throws RepositoryException
	 */
	public ContentName getKeyTargetFromObject(ContentObject content, ContentName target) throws RepositoryException {
		SignedInfo si = content.signedInfo();
		KeyLocator locator = si.getKeyLocator();
		if (null == locator)
			return null;
		if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
			Log.finer(Log.FAC_REPO, "Sync: Locator is: {0}", locator);
		if (locator.type() != KeyLocatorType.NAME)
			return null;
		if (PublicKeyObject.isSelfSigned(target, (PublicKey)null, locator))
			return null;
		
		// Here we are sort of mimicking code in PublicKeyCache. Should there be a routine to do
		// this in PublicKeyCache? (it would need to have a generic getter to get the data since
		// here we want to get it directly from the repo. Also I'm ignoring the "retry" code
		// there that does exclusions since I think its wrong, it would be complicated to do it
		// right and its unclear what kind of problem the code is concerned about...
		
		Interest keyInterest = new Interest(locator.name().name(), locator.name().publisher());
		// we could have from 1 (content digest only) to 3 (version, segment, content digest) 
		// additional name components.
		keyInterest.minSuffixComponents(1);
		keyInterest.maxSuffixComponents(3);

		ContentObject keyContent = _repo.getContent(keyInterest);
		if (null == keyContent) {
			if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
				Log.finer(Log.FAC_REPO, "Found key to sync: {0}", locator.name().name());
			return locator.name().name();
		}
		
		return getLinkedKeyTarget(keyContent);
	}
	
	/**
	 * Check whether co is a link and if so find any unsynced link target which is
	 * chained to it. 
	 * 
	 * @param co the ContentObject to test
	 * @return null if no unresolved target, ContentName of unresolved target otherwise
	 * @throws RepositoryException
	 */
	public ContentName getLinkedKeyTarget(ContentObject co) throws RepositoryException {
		while (co.isLink()) {
			Link link = new Link();
			try {
				link.decode(co.content());
				ContentName linkName = link.targetName();
				Interest linkInterest = new Interest(linkName);
				co = _repo.getContent(linkInterest);
				if (null == co) {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINER)) {
						Log.finer(Log.FAC_REPO, "Found linked key to sync: " + linkName);
					}
					return linkName;
				}
			} catch (ContentDecodingException e) {
				Log.warning(Log.FAC_REPO, "Couldn't decode link that is chained to a key locator: {0}", co.name());
				break;
			}
		}
		return null;
	}
	
	public void doSync(Interest interest, Interest readInterest) throws IOException {
		if (Log.isLoggable(Log.FAC_REPO, Level.FINER))
			Log.finer(Log.FAC_REPO, "Repo checked write no content for {0}, starting read", interest.name());
		RepositoryDataListener listener;
		listener = new RepositoryDataListener(interest, readInterest, this);
		addListener(listener);
		listener.getInterests().add(readInterest, null);
		_handle.expressInterest(readInterest, listener);
	}
	
	public Object getStatus(String type) {
		return _repo.getStatus(type);
	}
	
	// ==============================================================
	// Statistics
	
	protected CCNEnumStats<StatsEnum> _stats = new CCNEnumStats<StatsEnum>(StatsEnum.HandleInterest);

	public CCNStats getStats() {
		return _stats;
	}
	
	public enum StatsEnum implements IStatsEnum {
		// ====================================
		// Just edit this list, dont need to change anything else
		
		HandleInterest ("interests", "Number of calls to RepositoryInterestHandler.handleInterest()"),
		HandleInterestErrors ("errors", "Errors in handleInterest()"),
		
		HandleInterestCommands ("interests", "Number of command interests to handleInterest()"),
		
		HandleInterestStartWriteReceived ("interests", "Number of start writes to handleInterest()"),
		HandleInterestStartWriteProcessed ("interests", "Number of start writes processed by handleInterest()"),
		HandleInterestStartWriteIgnored ("interests", "Number of start writes ignored in handleInterest()"),
		HandleInterestStartWriteErrors ("errors", "Error count in startWrite()"),
		
		HandleInterestNameEnumReceived ("interests", "Number of name enums to handleInterest()"),
		HandleInterestNameEnumProcessed ("interests", "Number of name enums processed in handleInterest()"),
		HandleInterestNameEnumIgnored ("interests", "Number of name enums ignored in handleInterest()"),

		HandleInterestCheckedWriteReceived ("interests", "Number of checked write to handleInterest()"),
		HandleInterestCheckedWriteProcessed ("interests", "Number of checked write processed in handleInterest()"),
		HandleInterestCheckedWriteIgnored ("interests", "Number of checked write ignored in handleInterest()"),
		
		HandleInterestBulkImportReceived ("interests", "Number of bulk imports to handleInterest()"),
		HandleInterestBulkImportProcessed ("interests", "Number of bulk imports processed in handleInterest()"),
		HandleInterestBulkImportIgnored ("interests", "Number of bulk imports ignored in handleInterest()"),

		HandleInterestUncategorized ("interests", "Number of uncategorized interests to handleInterest()"),
		HandleInterestDuplicateRequests ("interests", "Number of duplicate interests to handleInterest()"),
		HandleInterestWriteSuspended ("interests", "Number of write suspended interests to handleInterest()"),
		HandleInterestStartWritePolicyHandlers ("responses", "Number of RepositoryPolicyHandler created for StartWrite"),
		HandleInterestStartWriteExpressInterest ("responses", "Number of expressInterests created for StartWrite"),
		HandleInterestNameEnumResponses ("responses", "Number of responses sent for Name Enums"),
		
		HandleContent ("objects", "Calls to ResponsitoryDataListener.handleContent()"),
		HandleContentHandleData ("objects", "Calls to handleData in RepositoryDataListener"),
		HandleContentExpressInterest ("interests", "Number of interests expressed in handleContent()"),
		HandleContentCancelInterest ("interests", "Number of interests cancelled"),
		HandleContentExpressInterestErrors ("errors", "Number of errors expressing interests in handleContent()"),
;


		// ====================================
		// This is the same for every user of IStatsEnum
		
		protected final String _units;
		protected final String _description;
		protected final static String [] _names;

		static {
			_names = new String[StatsEnum.values().length];
			for(StatsEnum stat : StatsEnum.values() )
				_names[stat.ordinal()] = stat.toString();

		}

		StatsEnum(String units, String description) {
			_units = units;
			_description = description;
		}

		public String getDescription(int index) {
			return StatsEnum.values()[index]._description;
		}

		public int getIndex(String name) {
			StatsEnum x = StatsEnum.valueOf(name);
			return x.ordinal();
		}

		public String getName(int index) {
			return StatsEnum.values()[index].toString();
		}

		public String getUnits(int index) {
			return StatsEnum.values()[index]._units;
		}

		public String [] getNames() {
			return _names;
		}
	}
}
