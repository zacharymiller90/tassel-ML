/*
 * FilterSiteNamePlugin.java
 *
 * Created on November 2, 2011
 *
 */
package net.maizegenetics.baseplugins;

import net.maizegenetics.pal.alignment.Alignment;
import net.maizegenetics.pal.alignment.FilterAlignment;

import net.maizegenetics.plugindef.AbstractPlugin;
import net.maizegenetics.plugindef.DataSet;
import net.maizegenetics.plugindef.Datum;
import net.maizegenetics.plugindef.PluginEvent;

import javax.swing.*;

import java.awt.Frame;

import java.net.URL;

import java.util.List;

import net.maizegenetics.gui.AbstractAvailableListModel;
import net.maizegenetics.gui.SelectFromAvailableDialog;

import org.apache.log4j.Logger;

/**
 *
 * @author terry
 */
public class FilterSiteNamePlugin extends AbstractPlugin {

    private static final Logger myLogger = Logger.getLogger(FilterSiteNamePlugin.class);
    private int[] mySitesToKeep = null;
    private String[] mySiteNamesToKeep = null;

    /** Creates a new instance of FilterSiteNamePlugin */
    public FilterSiteNamePlugin(Frame parentFrame, boolean isInteractive) {
        super(parentFrame, isInteractive);
    }

    public DataSet performFunction(DataSet input) {

        try {

            List inputData = input.getDataOfType(Alignment.class);
            if (inputData.size() != 1) {
                if (isInteractive()) {
                    JOptionPane.showMessageDialog(getParentFrame(), "Invalid selection. Please select a single alignment.");
                } else {
                    myLogger.error("performFunction: Please input a single alignment.");
                }
                return null;
            }

            Datum td = processDatum((Datum) inputData.get(0), isInteractive());
            if (td == null) {
                return null;
            }

            DataSet output = new DataSet(td, this);

            fireDataSetReturned(new PluginEvent(output, FilterSiteNamePlugin.class));

            return output;

        } finally {
            fireProgress(100);
        }
    }

    private Datum processDatum(Datum inDatum, boolean isInteractive) {

        final Alignment alignment = (Alignment) inDatum.getData();

        if (isInteractive) {
            AbstractAvailableListModel listModel = new AbstractAvailableListModel() {

                @Override
                public int getRealSize() {
                    return alignment.getSiteCount();
                }

                @Override
                public String getRealElementAt(int index) {
                    return alignment.getSNPID(index);
                }
            };
            SelectFromAvailableDialog dialog = new SelectFromAvailableDialog(getParentFrame(), "Site Name Filter", listModel);
            dialog.setLocationRelativeTo(getParentFrame());
            dialog.setVisible(true);
            if (dialog.isCanceled()) {
                return null;
            }
            mySitesToKeep = dialog.getDesiredIndices();
            dialog.dispose();
        }

        Alignment result = null;

        if (((mySitesToKeep != null) && (mySitesToKeep.length != 0))) {
            result = FilterAlignment.getInstance(alignment, mySitesToKeep);
        } else if (((mySiteNamesToKeep != null) && (mySiteNamesToKeep.length != 0))) {
            result = FilterAlignment.getInstance(alignment, mySiteNamesToKeep);
        } else {
            return null;
        }

        String theName, theComment;
        theName = inDatum.getName() + "_" + result.getSiteCount() + "_Sites";
        theComment = "Subset of " + result.getSiteCount() + " from " + alignment.getSiteCount() + " Sites\n" + inDatum.getComment();
        return new Datum(theName, result, theComment);

    }

    public int[] getSitesToKeep() {
        return mySitesToKeep;
    }

    public void setSitesToKeep(int[] sitesToKeep) {
        if (mySiteNamesToKeep != null) {
            throw new IllegalStateException("FilterSiteNamePlugin: setIdsToKeep: Can't set both sites and site names.");
        }
        mySitesToKeep = sitesToKeep;
    }

    public String[] getSiteNamesToKeep() {
        return mySiteNamesToKeep;
    }

    public void setSiteNamesToKeep(String[] sitesToKeep) {
        if (mySitesToKeep != null) {
            throw new IllegalStateException("FilterSiteNamePlugin: setIdsToKeep: Can't set both sites and site names.");
        }
        mySiteNamesToKeep = sitesToKeep;
    }

    /**
     * Icon for this plugin to be used in buttons, etc.
     *
     * @return ImageIcon
     */
    public ImageIcon getIcon() {
        URL imageURL = FilterSiteNamePlugin.class.getResource("images/Filter.gif");
        if (imageURL == null) {
            return null;
        } else {
            return new ImageIcon(imageURL);
        }
    }

    /**
     * Button name for this plugin to be used in buttons, etc.
     *
     * @return String
     */
    public String getButtonName() {
        return "Site Names";
    }

    /**
     * Tool Tip Text for this plugin
     *
     * @return String
     */
    public String getToolTipText() {
        return "Select Site Names Within Dataset";
    }
}
