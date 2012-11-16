package org.opennms.features.topology.api.topo;

import java.util.Collection;
import java.util.List;

public interface EdgeProvider {

	public String getNamespace();
	
	public Edge getEdge(String id);
	
	public Edge getEdge(EdgeRef reference);
	
	/**
	 * Return an immutable list of edges that match the criteria.
	 */
	public List<? extends Edge> getEdges(Criteria criteria);
	
	/**
	 * Return an immutable list of all edges.
	 */
	public List<? extends Edge> getEdges();
	
	/**
	 * Return an immutable list of all edges that match this set of references.
	 */
	public List<? extends Edge> getEdges(Collection<? extends EdgeRef> references);
	
	public void addEdgeListener(EdgeListener vertexListener);
	
	public void removeEdgeListener(EdgeListener vertexListener);

}
