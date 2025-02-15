package kuke.board.comment.service;

import jakarta.transaction.Transactional;
import kuke.board.comment.entity.Comment;
import kuke.board.comment.repository.CommentRepository;
import kuke.board.comment.service.request.CommentCreateRequest;
import kuke.board.comment.service.response.CommentPageResponse;
import kuke.board.comment.service.response.CommentResponse;
import kuke.board.common.event.Snowflake;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.function.Predicate.not;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final Snowflake snowflake = new Snowflake();
    private final CommentRepository commentRepository;

    @Transactional
    public CommentResponse create(CommentCreateRequest request) {
        // 부모 찾기
        Comment parent = findParent(request);
        Comment comment = commentRepository.save(
                Comment.create(
                        snowflake.nextId(),
                        request.getContent(),
                        parent == null ? null : parent.getCommentId(),
                        request.getArticleId(),
                        request.getWriterId()
                )
        );

        return CommentResponse.from(comment);
    }

    private Comment findParent(CommentCreateRequest request) {
        Long parentCommentId = request.getParentCommentId();
        // 상위 댓글이 존재하지 않음
        if (parentCommentId == null) {
            return null;
        }
        // 상위댓글 존재하니까 찾아서 반환
        return commentRepository.findById(parentCommentId)
                .filter(not(Comment::getDeleted)) // 상위 댓글이 삭제되지 않아야함
                .filter(Comment::isRoot) // 찾은 상위 댓글은 루트댓글
                .orElseThrow(); // 아닌경우는 예외
    }

    public CommentResponse read(Long commentId) {
        return CommentResponse.from(
                commentRepository.findById(commentId).orElseThrow()
        );
    }

    @Transactional
    public void delete(Long commentId) {
        commentRepository.findById(commentId)
                .filter(not(Comment::getDeleted)) // 삭제되지 않고
                .ifPresent(comment -> {
                    // 하위 댓글이 있으면 삭제표시만
                    if (hasChildren(comment)) {
                        comment.delete();
                    } else {
                        delete(comment);
                    }
                });
    }

    // 하위 댓글이 있는지 확인하는 메소드
    private boolean hasChildren(Comment comment) {
        return commentRepository.countBy(comment.getArticleId(), comment.getCommentId(), 2L) == 2;
    }

    private void delete(Comment comment) {
        commentRepository.delete(comment);

        // 루트댓글이 아니기 때문에 위에 상위 댓글이 있을 수 있다.
        if (!comment.isRoot()) {
            commentRepository.findById(comment.getParentCommentId()) // 상위댓글 있는지 확인
                    .filter(Comment::getDeleted) // 상위댓글이 삭제상태인지 확인
                    .filter(not(this::hasChildren)) // 자식댓글이 없는지 확인
                    .ifPresent(this::delete); // 모두 해당하면 상위댓글 완전삭제
        }
    }

    public CommentPageResponse readAll(Long articleId, Long page, Long pageSize) {
        return CommentPageResponse.of(
                commentRepository.findAll(articleId, (page - 1) * pageSize, pageSize).stream()
                        .map(CommentResponse::from)
                        .toList(),
                commentRepository.count(articleId, PageLimitCalculator.calculatePageLimit(page, pageSize, 10L))
        );
    }

    public List<CommentResponse> readAll(Long articleId, Long lastParentCommentId, Long lastCommentId, Long limit) {
        List<Comment> comments = lastParentCommentId == null || lastCommentId == null ?
                commentRepository.findAllInfiniteScroll(articleId, limit) :
                commentRepository.findAllInfiniteScroll(articleId, lastParentCommentId, lastCommentId, limit);
        return comments.stream()
                .map(CommentResponse::from)
                .toList();
    }
}
