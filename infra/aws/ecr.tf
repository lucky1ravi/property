resource "aws_ecr_repository" "backend" {
  name                 = "propertyos/backend"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # demo: destroy even when images remain
  image_scanning_configuration {
    scan_on_push = true
  }
}

# Every service has its own images, tagged <service>-<commit>. Keep the latest few of EACH, so a busy service
# can never push another service's running image out of the repository.
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = concat(
      [for i, name in sort(keys(local.services)) : {
        rulePriority = i + 1
        description  = "Keep the 8 most recent ${name} images"
        selection    = { tagStatus = "tagged", tagPrefixList = ["${name}-"], countType = "imageCountMoreThan", countNumber = 8 }
        action       = { type = "expire" }
      }],
      [{
        rulePriority = 100
        description  = "Remove untagged images after a week"
        selection    = { tagStatus = "untagged", countType = "sinceImagePushed", countUnit = "days", countNumber = 7 }
        action       = { type = "expire" }
      }]
    )
  })
}
