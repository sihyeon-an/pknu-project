package com.example.pknu_backend.Controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Slf4j
public class CommonController {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${server.port}")
    private String serverPort;

    public CommonController() {
    }

    @PostConstruct
    @Transactional
    public void createTablesAndUploadDirIfNotExists() {
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("Upload directory created: {}", uploadPath.toAbsolutePath());
            } else {
                log.info("Upload directory already exists: {}", uploadPath.toAbsolutePath());
            }

            String createBasempImageTableSql = "CREATE TABLE IF NOT EXISTS `basemp_image` (" +
                    "`empnum` VARCHAR(10) NOT NULL," +
                    "`image_id` INT AUTO_INCREMENT PRIMARY KEY," +
                    "`image_url` VARCHAR(255)," +
                    "`upload_date` DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (`empnum`) REFERENCES `basemp`(`empnum`) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;";
            entityManager.createNativeQuery(createBasempImageTableSql).executeUpdate();
            log.info("Table 'basemp_image' created or already exists.");

            // lost_found_items 테이블에 status 컬럼이 ENUM('ACTIVE', 'RESOLVED')으로 설정되어 있는지 확인 (없다면 추가/수정)
            // PostConstruct에서 테이블 생성 쿼리에 이미 포함되어 있음을 확인했습니다.
            String createLostFoundItemsTableSql = "CREATE TABLE IF NOT EXISTS `lost_found_items` (" +
                    "`item_id` INT AUTO_INCREMENT PRIMARY KEY," +
                    "`item_type` ENUM('LOST', 'FOUND') NOT NULL," +
                    "`title` VARCHAR(255) NOT NULL," +
                    "`description` TEXT," +
                    "`location` VARCHAR(255)," +
                    "`item_date` DATE," +
                    "`contact_info` VARCHAR(255)," +
                    "`image_url` VARCHAR(255)," +
                    "`status` ENUM('ACTIVE', 'RESOLVED') DEFAULT 'ACTIVE'," + // ⭐ status 필드 확인
                    "`posted_by_userid` VARCHAR(10) NOT NULL," +
                    "`posted_at` DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (`posted_by_userid`) REFERENCES `basemp`(`empnum`) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;";
            entityManager.createNativeQuery(createLostFoundItemsTableSql).executeUpdate();
            log.info("Table 'lost_found_items' created or already exists.");

        } catch (Exception e) {
            log.error("Error creating tables or upload directory: {}", e.getMessage());
        }
    }

    @PostMapping("/api/upload")
    @Transactional
    public ResponseEntity<?> uploadImage(@RequestParam("image") MultipartFile file,
                                         @RequestParam("userid") String userId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("업로드할 파일이 없습니다.");
        }

        try {
            String checkUserSql = "SELECT COUNT(*) FROM basemp WHERE empnum = ?";
            Query checkUserQuery = entityManager.createNativeQuery(checkUserSql);
            checkUserQuery.setParameter(1, userId);
            Long userCount = ((Number) checkUserQuery.getSingleResult()).longValue();

            if (userCount == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("존재하지 않는 사용자 ID입니다.");
            }

            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueFileName = UUID.randomUUID().toString() + fileExtension;
            Path filePath = Paths.get(uploadDir, uniqueFileName);

            Files.copy(file.getInputStream(), filePath);
            log.info("File saved to: {}", filePath.toAbsolutePath());

            String imageUrl = "/uploads/" + uniqueFileName;

            Map<String, Object> response = new HashMap<>();
            response.put("message", "이미지 업로드 성공!");
            response.put("imageUrl", imageUrl);

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("이미지 파일 저장 또는 처리 중 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("이미지 처리 오류: " + e.getMessage());
        } catch (Exception e) {
            log.error("이미지 업로드 중 DB 사용자 확인 오류 또는 기타 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("이미지 업로드 서버 오류: " + e.getMessage());
        }
    }

    @GetMapping("/api/items")
    public ResponseEntity<List<Map<String, Object>>> getAllItems() {
        String sql = "SELECT item_id, item_type, title, description, location, item_date, contact_info, image_url, status, posted_by_userid, posted_at " +
                "FROM lost_found_items ORDER BY posted_at DESC";
        Query query = entityManager.createNativeQuery(sql);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> itemList = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("itemId", row[0]);
            item.put("itemType", row[1]);
            item.put("title", row[2]);
            item.put("description", row[3]);
            item.put("location", row[4]);
            item.put("itemDate", row[5]);
            item.put("contactInfo", row[6]);
            item.put("imageUrl", row[7]);
            item.put("status", row[8]);
            item.put("postedByUserId", row[9]);
            item.put("postedAt", row[10]);

            if (row[7] != null && !((String) row[7]).isEmpty()) {
                String fullImageUrl = "http://localhost:" + serverPort + item.get("imageUrl");
                item.put("fullImageUrl", fullImageUrl);
            } else {
                item.put("fullImageUrl", null);
            }

            itemList.add(item);
        }
        log.info("분실물/습득물 목록 조회 성공: {} 건", itemList.size());
        return ResponseEntity.ok(itemList);
    }

    @PostMapping("/api/items")
    @Transactional
    public ResponseEntity<?> createItem(@RequestBody Map<String, String> itemData) {
        String itemType = itemData.get("itemType");
        String title = itemData.get("title");
        String description = itemData.get("description");
        String location = itemData.get("location");
        String itemDateStr = itemData.get("itemDate");
        String contactInfo = itemData.get("contactInfo");
        String imageUrl = itemData.get("imageUrl");
        String postedByUserId = itemData.get("postedByUserId");

        if (itemType == null || title == null || postedByUserId == null || itemDateStr == null || contactInfo == null) {
            return ResponseEntity.badRequest().body("필수 필드(유형, 제목, 날짜, 연락처, 작성자)가 누락되었습니다.");
        }

        LocalDate itemDate = null;
        try {
            itemDate = LocalDate.parse(itemDateStr);
        } catch (Exception e) {
            log.error("날짜 파싱 오류: {}", itemDateStr, e);
            return ResponseEntity.badRequest().body("날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)");
        }

        try {
            String insertSql = "INSERT INTO lost_found_items (item_type, title, description, location, item_date, contact_info, image_url, posted_by_userid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            Query query = entityManager.createNativeQuery(insertSql);
            query.setParameter(1, itemType);
            query.setParameter(2, title);
            query.setParameter(3, description);
            query.setParameter(4, location);
            query.setParameter(5, itemDate);
            query.setParameter(6, contactInfo);
            query.setParameter(7, imageUrl);
            query.setParameter(8, postedByUserId);

            query.executeUpdate();

            log.info("게시물 등록 성공: {}", title);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("게시물 등록 실패:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: 게시물 등록 실패 - " + e.getMessage());
        }
    }

    @PutMapping("/api/items/{itemId}")
    @Transactional
    public ResponseEntity<?> updateItem(@PathVariable int itemId, @RequestBody Map<String, Object> itemData) {
        try {
            String updateSql = "UPDATE lost_found_items SET item_type=?, title=?, description=?, location=?, item_date=?, contact_info=?, image_url=?, status=? WHERE item_id=?";
            Query query = entityManager.createNativeQuery(updateSql);
            query.setParameter(1, itemData.get("itemType"));
            query.setParameter(2, itemData.get("title"));
            query.setParameter(3, itemData.get("description"));
            query.setParameter(4, itemData.get("location"));
            query.setParameter(5, LocalDate.parse((String) itemData.get("itemDate")));
            query.setParameter(6, itemData.get("contactInfo"));
            query.setParameter(7, itemData.get("imageUrl"));
            query.setParameter(8, itemData.get("status"));
            query.setParameter(9, itemId);
            int result = query.executeUpdate();
            return result > 0 ? ResponseEntity.ok("수정 완료") : ResponseEntity.status(404).body("해당 항목 없음");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("수정 실패: " + e.getMessage());
        }
    }

    @DeleteMapping("/api/items/{itemId}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable("itemId") int itemId) {
        try {
            String deleteSql = "DELETE FROM lost_found_items WHERE item_id = ?";
            Query query = entityManager.createNativeQuery(deleteSql);
            query.setParameter(1, itemId);
            int result = query.executeUpdate();
            return result > 0 ? ResponseEntity.ok("삭제 완료") : ResponseEntity.status(404).body("해당 항목 없음");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("삭제 실패: " + e.getMessage());
        }
    }



    @PostMapping("/api/login1")
    public ResponseEntity login1(@RequestBody Map<String, String> params, HttpServletResponse res) {
        String userid = params.get("s_userid");
        String userpass = params.get("s_userpass");
        log.info("아이디: " + userid + ", 비밀번호: " + userpass);
        String sql = "SELECT * FROM basemp WHERE empnum = ? AND emppas = ?";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, userid);
            query.setParameter(2, userpass);
            List<Object[]> results = query.getResultList();
            log.info("results: {}", results);
            if (!results.isEmpty()) {
                Object[] row = results.get(0);
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("userid", row[0]);
                resultMap.put("userpass", row[1]);
                resultMap.put("username", row[2]);
                resultMap.put("usermail", row[3]);
                log.info("Employee Info: {}", resultMap);
                return ResponseEntity.ok(resultMap);
            } else {
                return ResponseEntity.ok("NOT");
            }
        } catch (Exception e) {
            log.error("Login error: ", e);
            return ResponseEntity.internalServerError().body("Exception: Login failed");
        }
    }

    @PostMapping("/api/register")
    @Transactional
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> params) {
        String userid = params.get("userid");
        String userpass = params.get("userpass");
        String username = params.get("username");
        String usermail = params.get("usermail");
        log.info("회원가입 요청: {}, {}, {}, {}", userid, userpass, username, usermail);
        String sql = "INSERT INTO basemp (empnum, emppas, empnam, empmal) VALUES (?, ?, ?, ?)";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, userid);
            query.setParameter(2, userpass);
            query.setParameter(3, username);
            query.setParameter(4, usermail);
            query.executeUpdate();
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("회원 등록 실패:", e);
            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
        }
    }

}