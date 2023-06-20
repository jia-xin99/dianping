package com.dp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.UUID;

import static com.dp.utils.SystemConstants.IMAGE_UPLOAD_DIR;

@Slf4j
@RequestMapping("/upload")
@RestController
public class UploadController {

    /**
     * @param image: 图片
     * @Description: 上传笔记中使用的图片
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名
            String originalFilename = image.getOriginalFilename();
            // 生成新的文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件到前端页面中（一般是保存在oss中，返回访问地址即可）
            image.transferTo(new File(IMAGE_UPLOAD_DIR + fileName));
            // 返回结果
            log.info("文件上传成功:{}", fileName);
            return Result.ok(fileName);
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String fileName) {
        File file = new File(IMAGE_UPLOAD_DIR, fileName);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}

