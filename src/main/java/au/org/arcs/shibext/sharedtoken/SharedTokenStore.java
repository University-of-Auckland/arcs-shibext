/**
 * 
 */
package au.org.arcs.shibext.sharedtoken;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Damien Chen
 * 
 */
public class SharedTokenStore {

	/** Class logger. */
	private final Logger log = LoggerFactory.getLogger(SharedTokenStore.class);

	private DataSource dataSource;

	public SharedTokenStore(DataSource dataSource) {

		this.dataSource = dataSource;

	}

	public String getSharedToken(String uid)
			throws IMASTException {
		log.debug("calling getSharedToken ...");

		Connection conn = null;
		String sharedToken = null;
		PreparedStatement st = null;
		ResultSet rs = null;

		try {

			try {
				conn = dataSource.getConnection();
				st = conn.prepareStatement("SELECT sharedToken from tb_st WHERE uid=?");
				st.setString(1, uid);
				log.debug("SELECT sharedToken from tb_st WHERE uid=" + uid);
				rs = st.executeQuery();

				while (rs.next()) {
					sharedToken = rs.getString("sharedToken");
				}
			} catch (SQLException e) {
				log.error("Error executing SQL statement", e);
				throw new IMASTException("Error executing SQL statement", e);
			} finally {
				try {
					rs.close();
					conn.close();
				} catch (SQLException e) {
					throw new IMASTException("Error closing database connection", e);
				}
			}
		} catch (Exception e) {
			log.error("Failed to get SharedToken from database", e);
			throw new IMASTException("Failed to get SharedToken from database", e);
		}
		log.debug("SharedTokenStore: found value {} for uid {}", sharedToken, uid);

		return sharedToken;
	}

	public void storeSharedToken(String uid, String sharedToken) throws IMASTException {
		log.info("SharedTokenStore: storing value {} for uid {}", sharedToken, uid);
		Connection conn = null;
		PreparedStatement st = null;

		try {

			try {
				conn = dataSource.getConnection();
				st = conn.prepareStatement("INSERT INTO tb_st VALUES (?, ?)");
				st.setString(1, uid);
				st.setString(2, sharedToken);
				st.executeUpdate();
				log.debug("INSERT INTO tb_st VALUES ('{}', '{}')", uid, sharedToken);
				log.debug("Successfully stored the SharedToken value into database");
			} catch (SQLException e) {
				log.error("Failed to store SharedToken into database", e);
				throw new IMASTException("Failed to store the SharedToken value into database", e);
			} finally {
				try {
					conn.close();
				} catch (SQLException e) {
					throw new IMASTException("Error closing database connection", e);
				}
			}
		} catch (Exception e) {
			log.error("Failed to store SharedToken into database", e);
			throw new IMASTException("Failed to store SharedToken into database", e);
		}

	}
}
