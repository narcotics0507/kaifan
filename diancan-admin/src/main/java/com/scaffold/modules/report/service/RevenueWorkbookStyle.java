package com.scaffold.modules.report.service;

import com.alibaba.excel.write.handler.CellWriteHandler;
import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.handler.context.CellWriteHandlerContext;
import com.alibaba.excel.write.handler.context.SheetWriteHandlerContext;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Keep receipt identifiers as text and money readable in the exported workbook. */
public class RevenueWorkbookStyle implements CellWriteHandler, SheetWriteHandler {
    private final Map<Integer, Set<Integer>> moneyColumns = Map.of(
            0, Set.of(1, 2, 3, 5, 6, 7, 8, 10, 11, 12),
            1, Set.of(6, 7, 8, 9, 10, 11, 12, 13),
            2, Set.of(4, 6), 3, Set.of(6), 4, Set.of(6));
    private final Map<Short, CellStyle> styles = new HashMap<>();

    @Override
    public void afterSheetCreate(SheetWriteHandlerContext context) {
        context.getWriteSheetHolder().getSheet().createFreezePane(0, 1);
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        if (Boolean.TRUE.equals(context.getHead())) return;
        var cell = context.getCell();
        int sheet = context.getWriteSheetHolder().getSheetNo();
        if (cell.getCellType() != CellType.NUMERIC || !moneyColumns.getOrDefault(sheet, Set.of()).contains(cell.getColumnIndex())) return;
        short base = cell.getCellStyle().getIndex();
        CellStyle style = styles.computeIfAbsent(base, ignored -> {
            var workbook = cell.getSheet().getWorkbook();
            CellStyle result = workbook.createCellStyle();
            result.cloneStyleFrom(cell.getCellStyle());
            result.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00;[Red]-#,##0.00"));
            return result;
        });
        cell.setCellStyle(style);
    }
}
