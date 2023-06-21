package com.dp.dto;

import lombok.Data;

import java.util.List;

/**
 * 返回滚动分页（关注的博客推送列表）的结果
 * minTime：本次分页最后一条博客消息的时间戳
 * offset：本次分页最后一条博客消息在其相同时间戳中的偏移位置（即该条前有x条和其时间戳相同的消息，便于下次查询时偏移）---解决同一时间戳有多条博客
 * list：返回的分页结果
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
