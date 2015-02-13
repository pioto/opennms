package org.opennms.features.vaadin.surveillanceviews.service;

import org.opennms.features.vaadin.surveillanceviews.model.Category;
import org.opennms.features.vaadin.surveillanceviews.model.View;
import org.opennms.netmgt.model.OnmsAlarm;
import org.opennms.netmgt.model.OnmsCategory;
import org.opennms.netmgt.model.OnmsNode;
import org.opennms.netmgt.model.OnmsNotification;
import org.opennms.netmgt.model.SurveillanceStatus;

import java.text.DecimalFormat;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interface for the surveillance view service.
 */
public interface SurveillanceViewService {
    List<OnmsCategory> getOnmsCategories();

    /*
    List<String> getRtcCategories();
    */

    SurveillanceStatus[][] calculateCellStatus(final View view);

    Set<OnmsCategory> getOnmsCategoriesFromViewCategories(final Collection<Category> viewCats);

    List<OnmsAlarm> getAlarmsForCategories(Set<OnmsCategory> rowCategories, Set<OnmsCategory> colCategories);

    List<OnmsNotification> getNotificationsForCategories(Set<OnmsCategory> rowCategories, Set<OnmsCategory> colCategories, Map<OnmsNotification, String> customSeverity);

    List<NodeRtc> getNoteRtcsForCategories(Set<OnmsCategory> rowCategories, Set<OnmsCategory> colCategories);

    class NodeRtc {
        private static final DecimalFormat AVAILABILITY_FORMAT = new DecimalFormat("0.000%");

        static {
            AVAILABILITY_FORMAT.setMultiplier(100);
        }

        private OnmsNode m_node;
        private int m_serviceCount;
        private int m_downServiceCount;
        private double m_availability;

        public NodeRtc(OnmsNode node, int serviceCount, int downServiceCount, double availability) {
            m_node = node;
            m_serviceCount = serviceCount;
            m_downServiceCount = downServiceCount;
            m_availability = availability;
        }

        public double getAvailability() {
            return m_availability;
        }

        public String getAvailabilityAsString() {
            return AVAILABILITY_FORMAT.format(m_availability);
        }

        public int getDownServiceCount() {
            return m_downServiceCount;
        }

        public OnmsNode getNode() {
            return m_node;
        }

        public int getServiceCount() {
            return m_serviceCount;
        }

        @Override
        public String toString() {
            return m_node.getLabel() + ": " + m_downServiceCount + " of " + m_serviceCount + ": " + getAvailabilityAsString();
        }
    }
}
