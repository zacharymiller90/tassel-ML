/*
 *  PositionListTableReport
 * 
 *  Created on Jul 7, 2014
 */
package net.maizegenetics.dna.map;

import java.util.ArrayList;
import java.util.List;

import net.maizegenetics.util.TableReport;

/**
 *
 * @author Terry Casstevens
 */
public class PositionListTableReport implements TableReport {

    private static final String[] DEFAULT_COLUMN_HEADINGS = new String[]{"Site", "Name", "Chromosome", "Position"};

    private final PositionList myPositionList;
    private final String[] myColumnHeadings;

    public PositionListTableReport(PositionList positionList) {
        myPositionList = positionList;
        List<String> annotationColumns = new ArrayList<>();
        for (Position current : myPositionList) {
            for (String key : current.getAnnotationAsMap().keySet()) {
                if (!annotationColumns.contains(key)) {
                    annotationColumns.add(key);
                }
            }
        }
        int totalHeadings = DEFAULT_COLUMN_HEADINGS.length + annotationColumns.size();
        myColumnHeadings = new String[totalHeadings];
        for (int i = 0; i < DEFAULT_COLUMN_HEADINGS.length; i++) {
            myColumnHeadings[i] = DEFAULT_COLUMN_HEADINGS[i];
        }
        for (int j = DEFAULT_COLUMN_HEADINGS.length; j < totalHeadings; j++) {
            myColumnHeadings[j] = annotationColumns.get(j - DEFAULT_COLUMN_HEADINGS.length);
        }
    }

    @Override
    public Object[] getTableColumnNames() {
        return myColumnHeadings;
    }

    @Override
    public Object[][] getTableData() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public String getTableTitle() {
        return "Position List";
    }

    @Override
    public int getColumnCount() {
        return myColumnHeadings.length;
    }

    @Override
    public int getRowCount() {
        return myPositionList.numberOfSites();
    }

    @Override
    public int getElementCount() {
        return getColumnCount() * getRowCount();
    }

    @Override
    public Object[] getRow(int row) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public Object[][] getTableData(int start, int end) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public Object getValueAt(int row, int col) {
        switch (col) {
            case 0:
                return row;
            case 1:
                return myPositionList.get(row).getSNPID();
            case 2:
                return myPositionList.get(row).getChromosome();
            case 3:
                return myPositionList.get(row).getPosition();
            default:
                String[] annotations = myPositionList.get(row).getTextAnnotation(myColumnHeadings[col]);
                if (annotations != null) {
                    return annotations[0];
                } else {
                    return null;
                }
        }
    }

}