package com.bretth.osmosis.core.mysql.impl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import com.bretth.osmosis.core.OsmosisRuntimeException;
import com.bretth.osmosis.core.data.Node;


/**
 * Reads all nodes from a database ordered by their identifier.
 * 
 * @author Brett Henderson
 */
public class NodeReader extends EntityReader<Node> {
	private static final String SELECT_SQL =
		"SELECT n.id, n.timestamp, n.latitude, n.longitude, n.tags"
		+ " FROM nodes n"
		+ " INNER JOIN"
		+ " ("
		+ "SELECT id, MAX(timestamp) AS timestamp"
		+ " FROM nodes"
		+ " WHERE timestamp < ?"
		+ " GROUP BY id"
		+ " ORDER BY id"
		+ ") n2 ON n.id = n2.id AND n.timestamp = n2.timestamp"
		+ " WHERE visible = 1";
	
	private EmbeddedTagProcessor tagParser;
	private Date snapshotInstant;
	private long previousId;
	private Date previousTimestamp;
	
	
	/**
	 * Creates a new instance.
	 * 
	 * @param host
	 *            The server hosting the database.
	 * @param database
	 *            The database instance.
	 * @param user
	 *            The user name for authentication.
	 * @param password
	 *            The password for authentication.
	 * @param snapshotInstant
	 *            The state of the node table at this point in time will be
	 *            dumped.  This ensures a consistent snapshot.
	 */
	public NodeReader(String host, String database, String user, String password, Date snapshotInstant) {
		super(host, database, user, password);
		
		this.snapshotInstant = snapshotInstant;
		tagParser = new EmbeddedTagProcessor();
		
		previousId = 0;
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected ResultSet createResultSet(DatabaseContext queryDbCtx) {
		try {
			PreparedStatement statement;
			
			statement = queryDbCtx.prepareStatementForStreaming(SELECT_SQL);
			statement.setTimestamp(1, new Timestamp(snapshotInstant.getTime()));
			
			return statement.executeQuery();
			
		} catch (SQLException e) {
			throw new OsmosisRuntimeException("Unable to create streaming resultset.", e);
		}
	}
	
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	protected ReadResult<Node> createNextValue(ResultSet resultSet) {
		long id;
		Date timestamp;
		double latitude;
		double longitude;
		String tags;
		Node node;
		boolean usableResult;
		
		try {
			id = resultSet.getLong("id");
			timestamp = new Date(resultSet.getTimestamp("timestamp").getTime());
			latitude = resultSet.getDouble("latitude");
			longitude = resultSet.getDouble("longitude");
			tags = resultSet.getString("tags");
			
		} catch (SQLException e) {
			throw new OsmosisRuntimeException("Unable to read node fields.", e);
		}
		
		node = new Node(id, timestamp, latitude, longitude);
		node.addTags(tagParser.parseTags(tags));
		
		if (id < previousId) {
			throw new OsmosisRuntimeException(
					"Id of " + id + " must be greater or equal to previous id of " + previousId + ".");
		} else if (id == previousId) {
			if (!timestamp.equals(previousTimestamp)) {
				throw new OsmosisRuntimeException(
						"Id of " + id + " has multiple records.");
			}
			
			// Two records exist with the same id and timestamp, we will ignore
			// this second one by flagging an invalid result.
			usableResult = false;
			
		} else {
			previousId = id;
			previousTimestamp = timestamp;
			
			usableResult = true;
		}
		
		return new ReadResult<Node>(usableResult, node);
	}
}