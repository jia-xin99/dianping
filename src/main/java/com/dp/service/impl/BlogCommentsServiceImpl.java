package com.dp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.entity.BlogComments;
import com.dp.mapper.BlogCommentsMapper;
import com.dp.service.BlogCommentsService;
import org.springframework.stereotype.Service;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements BlogCommentsService {

}
