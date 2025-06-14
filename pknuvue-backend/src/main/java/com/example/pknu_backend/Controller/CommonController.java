// CommonController.java 파일
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

            String createLostFoundItemsTableSql = "CREATE TABLE IF NOT EXISTS `lost_found_items` (" +
                    "`item_id` INT AUTO_INCREMENT PRIMARY KEY," +
                    "`item_type` ENUM('LOST', 'FOUND') NOT NULL," +
                    "`title` VARCHAR(255) NOT NULL," +
                    "`description` TEXT," +
                    "`location` VARCHAR(255)," +
                    "`item_date` DATE," +
                    "`contact_info` VARCHAR(255)," +
                    "`image_url` VARCHAR(255)," +
                    "`status` ENUM('ACTIVE', 'RESOLVED') DEFAULT 'ACTIVE'," +
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

    @DeleteMapping("/api/items/{itemId}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable Long itemId, @RequestParam("postedByUserId") String postedByUserId) {
        try {
            String checkSql = "SELECT posted_by_userid, image_url FROM lost_found_items WHERE item_id = ?";
            Query checkQuery = entityManager.createNativeQuery(checkSql);
            checkQuery.setParameter(1, itemId);
            Object[] result = (Object[]) checkQuery.getSingleResult();
            String actualUserId = (String) result[0];
            String imageUrl = (String) result[1];

            if (!actualUserId.equals(postedByUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("삭제 권한 없음");
            }

            Query deleteQuery = entityManager.createNativeQuery("DELETE FROM lost_found_items WHERE item_id = ?");
            deleteQuery.setParameter(1, itemId);
            deleteQuery.executeUpdate();

            if (imageUrl != null && !imageUrl.isEmpty()) {
                Path path = Paths.get(uploadDir, imageUrl.substring(imageUrl.lastIndexOf('/') + 1));
                Files.deleteIfExists(path);
            }

            return ResponseEntity.ok("OK");

        } catch (NoResultException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("게시물 없음");
        } catch (Exception e) {
            log.error("삭제 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("삭제 실패: " + e.getMessage());
        }
    }

    // 게시물 수정
    // ⭐ @PathVariable Long itemId를 @RequestPart("itemId") Long itemId로 변경
    @PutMapping(value = "/api/items", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE}) // ⭐ URL에서 {itemId} 제거
    @Transactional
    public ResponseEntity<?> updateItem(
            // ⭐ itemId를 @RequestPart로 받도록 변경
            @RequestPart("itemId") Long itemId, // itemId는 필수이므로 required = false 제거
            @RequestPart(required = false) MultipartFile imageFile,
            @RequestPart("itemType") String itemType,
            @RequestPart("title") String title,
            @RequestPart("description") String description,
            @RequestPart("location") String location,
            @RequestPart("itemDate") String itemDateStr,
            @RequestPart("contactInfo") String contactInfo,
            @RequestPart("status") String status,
            @RequestPart("postedByUserId") String postedByUserId
    ) {
        log.info("수정 요청: itemId={}, title={}, imageFile={}", itemId, title, imageFile != null ? imageFile.getOriginalFilename() : "없음");
        log.info("itemType: {}, description: {}, location: {}", itemType, description, location); // 추가 로깅
        log.info("itemDateStr: {}, contactInfo: {}, status: {}, postedByUserId: {}", itemDateStr, contactInfo, status, postedByUserId); // 추가 로깅

        try {
            // ... (기존 로직)

            // 기존 게시물 작성자 확인
            // 이 쿼리에서 itemId를 사용합니다.
            String ownerSql = "SELECT posted_by_userid, image_url FROM lost_found_items WHERE item_id = ?";
            Query ownerQuery = entityManager.createNativeQuery(ownerSql);
            ownerQuery.setParameter(1, itemId); // ⭐ 여기서는 이미 Long 타입의 itemId를 사용

            List<Object[]> ownerResults = ownerQuery.getResultList();
            if (ownerResults.isEmpty()) {
                log.error("수정할 게시물을 찾을 수 없습니다 (itemId: {})", itemId); // 추가 로깅
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("게시물을 찾을 수 없습니다.");
            }
            Object[] ownerResult = (Object[]) ownerResults.get(0); // getSingleResult() 대신 getResultList() 사용 권장
            String actualPostedBy = (String) ownerResult[0];
            String oldImageUrl = (String) ownerResult[1];

            if (!actualPostedBy.equals(postedByUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("권한 없음");
            }

            LocalDate itemDate = null;
            if (itemDateStr != null && !itemDateStr.trim().isEmpty()) {
                try {
                    itemDate = LocalDate.parse(itemDateStr);
                } catch (java.time.format.DateTimeParseException e) {
                    log.error("날짜 파싱 오류: {} (itemId: {}): {}", itemDateStr, itemId, e.getMessage(), e);
                    return ResponseEntity.badRequest().body("날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)");
                }
            } else {
                log.warn("itemDateStr이 비어있습니다 (itemId: {}). DB에 NULL로 저장될 수 있습니다.", itemId);
            }


            String newImageUrl = oldImageUrl;

            if (imageFile != null && !imageFile.isEmpty()) {
                String originalFilename = imageFile.getOriginalFilename();
                String fileExtension = "";
                if (originalFilename != null && originalFilename.contains(".")) {
                    fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
                }
                String uniqueFileName = UUID.randomUUID().toString() + fileExtension;
                Path filePath = Paths.get(uploadDir, uniqueFileName);
                Files.copy(imageFile.getInputStream(), filePath);
                newImageUrl = "/uploads/" + uniqueFileName;

                if (oldImageUrl != null && !oldImageUrl.isEmpty()) {
                    Path oldImagePath = Paths.get(uploadDir, oldImageUrl.substring(oldImageUrl.lastIndexOf('/') + 1));
                    Files.deleteIfExists(oldImagePath);
                    log.info("기존 이미지 삭제 완료: {}", oldImagePath);
                }
            }

            String updateSql = "UPDATE lost_found_items SET item_type = ?, title = ?, description = ?, location = ?, item_date = ?, contact_info = ?, image_url = ?, status = ? WHERE item_id = ?";
            Query updateQuery = entityManager.createNativeQuery(updateSql);
            updateQuery.setParameter(1, itemType);
            updateQuery.setParameter(2, title);
            updateQuery.setParameter(3, description);
            updateQuery.setParameter(4, location);
            updateQuery.setParameter(5, itemDate);
            updateQuery.setParameter(6, contactInfo);
            updateQuery.setParameter(7, newImageUrl);
            updateQuery.setParameter(8, status);
            updateQuery.setParameter(9, itemId);

            int updated = updateQuery.executeUpdate();
            return updated > 0 ? ResponseEntity.ok("OK") : ResponseEntity.status(HttpStatus.NOT_FOUND).body("게시물 없음");

        } catch (NoResultException e) {
            log.error("수정할 게시물을 찾을 수 없습니다 (itemId: {}): {}", itemId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("수정할 게시물을 찾을 수 없습니다.");
        }
        catch (Exception e) {
            log.error("게시물 수정 최종 오류 (itemId: {}): {}", itemId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("수정 실패: " + e.getMessage());
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
    //    @PostMapping("/api/login7")
//    public ResponseEntity<?> login7(@RequestBody Map<String, String> params) {
//        String server_username = params.get("s_username");
//        log.info("넘어온 검색이름: {}", server_username);
//        String sql = "SELECT * FROM basemp WHERE empnam LIKE ?";
//        try {
//            Query query = entityManager.createNativeQuery(sql);
//            query.setParameter(1, server_username + '%');
//            List<Object[]> results = query.getResultList();
//            log.info("results: {}", results);
//            if (!results.isEmpty()) {
//                List<Map<String, Object>> resultList = new ArrayList<>();
//                for (Object[] row : results) {
//                    Map<String, Object> resultMap = new HashMap<>();
//                    resultMap.put("userid", row[0]);
//                    resultMap.put("userpass", row[1]);
//                    resultMap.put("username", row[2]);
//                    resultMap.put("usermail", row[3]);
//                    resultList.add(resultMap);
//                }
//                log.info("조회 결과: {}", resultList);
//                return ResponseEntity.ok(resultList);
//            } else {
//                return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
//            }
//        } catch (Exception e) {
//            log.error("Login error: ", e);
//            return ResponseEntity.internalServerError().body("Exception Login failed");
//        }
//    }
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
//    @PostMapping("/api/update")
//    @Transactional
//    public ResponseEntity<?> updateUser(@RequestBody Map<String, String> params) {
//        String userid = params.get("userid");
//        String userpass = params.get("userpass");
//        String username = params.get("username");
//        String usermail = params.get("usermail");
//        String sql = "UPDATE basemp SET emppas = ?, empnam = ?, empmal = ? WHERE empnum = ?";
//        try {
//            Query query = entityManager.createNativeQuery(sql);
//            query.setParameter(1, userpass);
//            query.setParameter(2, username);
//            query.setParameter(3, usermail);
//            query.setParameter(4, userid);
//            int updatedCount = query.executeUpdate();
//            if (updatedCount > 0) {
//                return ResponseEntity.ok("OK");
//            } else {
//                return ResponseEntity.ok("NOT_FOUND");
//            }
//        } catch (Exception e) {
//            log.error("업데이트 실패:", e);
//            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
//        }
//    }
//    @PostMapping("/api/deleteMultiple")
//    @Transactional
//    public ResponseEntity<?> deleteMultipleUsers(@RequestBody Map<String, List<String>> params) {
//        List<String> userids = params.get("userids");
//        log.info("다중 삭제 요청 받은 userids: {}", userids);
//        if (userids == null || userids.isEmpty()) {
//            return ResponseEntity.badRequest().body("userids가 비어있음");
//        }
//        String placeholders = String.join(",", userids.stream().map(id -> "?").toArray(String[]::new));
//        String sql = "DELETE FROM basemp WHERE empnum IN (" + placeholders + ")";
//        try {
//            Query query = entityManager.createNativeQuery(sql);
//            for (int i = 0; i < userids.size(); i++) {
//                query.setParameter(i + 1, userids.get(i));
//            }
//            int deletedCount = query.executeUpdate();
//            log.info("삭제된 행 수: {}", deletedCount);
//            if (deletedCount > 0) {
//                return ResponseEntity.ok("OK");
//            } else {
//                return ResponseEntity.ok("NOT_FOUND");
//            }
//        } catch (Exception e) {
//            log.error("다중 삭제 실패:", e);
//            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
//        }
//    }
}